import CSQLite
import CZlib
import Foundation
import XCTest
@testable import BinarioKit

final class SQLiteGTFSJourneyRepositoryTests: XCTestCase {
    private let rome = TimeZone(identifier: "Europe/Rome")!
    private let origin = StationIdentity(id: "catalog-origin", name: "Roma Termini", railwayCode: "RM")
    private let destination = StationIdentity(id: "catalog-destination", name: "Milano Centrale", railwayCode: "MI")

    func testDirectRouteReturnsOnlyProgrammedStaticDataAndRealAttribution() async throws {
        let fixture = try Fixture()
        try fixture.insertTrip(id: 1, externalID: "direct", number: "FR 1", originSequence: 1, destinationSequence: 2, seconds: 10 * 3_600)
        let result = await fixture.repository.journeys(for: try query("2024-04-01 09:00"))

        guard case let .available(snapshot) = result else { return XCTFail("Expected a static snapshot") }
        XCTAssertEqual(snapshot.operationalState, .programmed)
        XCTAssertEqual(snapshot.provenance.nature, .programmed)
        XCTAssertEqual(snapshot.provenance.confidence, .staticOnly)
        XCTAssertEqual(snapshot.provenance.freshness, .unknown)
        XCTAssertEqual(snapshot.provenance.sourceLabel, "Synthetic official feed")
        XCTAssertEqual(snapshot.provenance.observedAt, Date(timeIntervalSince1970: 1_711_756_800))
        XCTAssertEqual(snapshot.services.count, 1)
        XCTAssertEqual(snapshot.services[0].number, "FR 1")
        XCTAssertEqual(snapshot.services[0].scheduledDeparture, "10:00")
        XCTAssertEqual(snapshot.services[0].expectedDeparture, "10:00")
        XCTAssertEqual(snapshot.services[0].status, "PROGRAMMED")
        XCTAssertNil(snapshot.services[0].platform)
        XCTAssertEqual(snapshot.services[0].delayMinutes, 0)
    }

    func testDateAfterTheLastServiceDayReportsAnExpiredSchedule() async throws {
        let fixture = try Fixture()
        try fixture.insertTrip(id: 1, externalID: "direct", number: "FR 1", originSequence: 1, destinationSequence: 2, seconds: 10 * 3_600)

        let expired = await fixture.repository.journeys(for: try query("2025-01-15 09:00"))
        let withinValidity = await fixture.repository.journeys(for: try query("2024-04-02 09:00"))

        XCTAssertEqual(expired, .unavailable(.scheduleExpired))
        XCTAssertEqual(withinValidity, .unavailable(.noJourney))
    }

    func testReverseStopSequenceIsRejected() async throws {
        let fixture = try Fixture()
        try fixture.insertTrip(id: 1, externalID: "reverse", originSequence: 2, destinationSequence: 1, seconds: 10 * 3_600)
        let result = await fixture.repository.journeys(for: try query("2024-04-01 09:00"))
        XCTAssertEqual(result, .unavailable(.noJourney))
    }

    func testCanonicalStationIdentityCannotBeSubstitutedBySameName() async throws {
        let fixture = try Fixture()
        try fixture.insertTrip(id: 1, externalID: "direct", seconds: 10 * 3_600)
        let impostorOrigin = StationIdentity(id: "different-catalog-id", name: origin.name, railwayCode: origin.railwayCode)
        let request = try JourneyQuery(
            origin: impostorOrigin,
            destination: destination,
            departure: try date("2024-04-01 09:00")
        )

        let result = await fixture.repository.journeys(for: request)
        XCTAssertEqual(result, .unavailable(.noJourney))
    }

    func testNoPickupAndNoDropOffSegmentsAreRejected() async throws {
        let noPickup = try Fixture()
        try noPickup.insertTrip(id: 1, externalID: "no-pickup", seconds: 10 * 3_600, pickupType: 1)
        let noPickupResult = await noPickup.repository.journeys(for: try query("2024-04-01 09:00"))
        XCTAssertEqual(noPickupResult, .unavailable(.noJourney))

        let noDropOff = try Fixture()
        try noDropOff.insertTrip(id: 1, externalID: "no-drop-off", seconds: 10 * 3_600, dropOffType: 1)
        let noDropOffResult = await noDropOff.repository.journeys(for: try query("2024-04-01 09:00"))
        XCTAssertEqual(noDropOffResult, .unavailable(.noJourney))
    }

    func testCalendarExceptionAdditionAndRemovalAreEncodedInBitset() async throws {
        let added = try Fixture(activeDates: [20240401])
        try added.insertTrip(id: 1, externalID: "added", seconds: 10 * 3_600)
        let addedResult = await added.repository.journeys(for: try query("2024-04-01 09:00"))
        guard case let .available(snapshot) = addedResult else { return XCTFail("Added date must run") }
        XCTAssertEqual(snapshot.services.count, 1)

        let removed = try Fixture(activeDates: [20240331, 20240402])
        try removed.insertTrip(id: 1, externalID: "removed", seconds: 10 * 3_600)
        let removedResult = await removed.repository.journeys(for: try query("2024-04-01 09:00"))
        XCTAssertEqual(removedResult, .unavailable(.noJourney))
    }

    func testPreviousServiceDayExtendedTimeUsesNoonOriginAcrossRomeDST() async throws {
        let fixture = try Fixture(activeDates: [20240330])
        try fixture.insertTrip(id: 1, externalID: "night", number: "N 42", seconds: 26 * 3_600 + 30 * 60)
        let result = await fixture.repository.journeys(for: try query("2024-03-31 03:00"))

        guard case let .available(snapshot) = result else { return XCTFail("Expected previous service-day train") }
        XCTAssertEqual(snapshot.services.map(\.number), ["N 42"])
        XCTAssertEqual(snapshot.services[0].scheduledDeparture, "03:30")
    }

    func testServiceDayMoreThan48HoursEarlierIsIncludedAcrossRomeDST() async throws {
        let fixture = try Fixture(activeDates: [20240330])
        try fixture.insertTrip(id: 1, externalID: "long-night", number: "N 50", seconds: 50 * 3_600 + 30 * 60)
        let result = await fixture.repository.journeys(for: try query("2024-04-01 03:00"))

        guard case let .available(snapshot) = result else { return XCTFail("Expected extended service-day train") }
        XCTAssertEqual(snapshot.services.map(\.number), ["N 50"])
        XCTAssertEqual(snapshot.services[0].scheduledDeparture, "03:30")
    }

    func testMalformedDatabaseAndMissingSchemaFailClosed() async throws {
        let malformedURL = temporaryURL()
        defer { try? FileManager.default.removeItem(at: malformedURL) }
        try Data("not sqlite".utf8).write(to: malformedURL)
        let malformed = SQLiteGTFSJourneyRepository(databaseURL: malformedURL)
        let malformedResult = await malformed.journeys(for: try query("2024-04-01 09:00"))
        XCTAssertEqual(malformedResult, .unavailable(.providerInvalid))

        let missingSchemaURL = temporaryURL()
        defer { try? FileManager.default.removeItem(at: missingSchemaURL) }
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(missingSchemaURL.path, &database), SQLITE_OK)
        sqlite3_exec(database, "PRAGMA application_id = \(SQLiteGTFSJourneyRepository.applicationID); PRAGMA user_version = 1; CREATE TABLE metadata(key TEXT, value TEXT);", nil, nil, nil)
        sqlite3_close(database)
        let missingSchema = SQLiteGTFSJourneyRepository(databaseURL: missingSchemaURL)
        let missingSchemaResult = await missingSchema.journeys(for: try query("2024-04-01 09:00"))
        XCTAssertEqual(missingSchemaResult, .unavailable(.providerInvalid))
    }

    func testBundledInstallerVerifiesAndReusesAValidDatabase() throws {
        let fixture = try Fixture()
        let database = try Data(contentsOf: fixture.url)
        let compressed = try zlibCompress(database)
        let temporary = FileManager.default.temporaryDirectory
            .appendingPathComponent("BinarioGTFSInstallerTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: temporary) }
        let compressedURL = temporary.appendingPathComponent("schedule.sqlite.zlib")
        let metadataURL = temporary.appendingPathComponent("schedule.metadata.json")
        let cacheURL = temporary.appendingPathComponent("cache", isDirectory: true)
        try compressed.write(to: compressedURL)
        let metadata = installerMetadata(database: database, compressed: compressed)
        try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]).write(to: metadataURL)
        try FileManager.default.createDirectory(at: cacheURL, withIntermediateDirectories: true)
        let obsolete = cacheURL.appendingPathComponent("binario-gtfs-obsolete.sqlite")
        try Data("previous-cache".utf8).write(to: obsolete)

        let installer = BundledGTFSScheduleInstaller()
        let installed = try installer.install(
            compressedURL: compressedURL,
            metadataURL: metadataURL,
            cacheDirectory: cacheURL
        )
        XCTAssertFalse(FileManager.default.fileExists(atPath: obsolete.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: installed.path))
        XCTAssertEqual(try Data(contentsOf: installed), database)

        #if os(iOS) || os(macOS) || os(watchOS) || os(tvOS) || os(visionOS)
        XCTAssertEqual(try installed.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = false
        var mutableInstalled = installed
        try mutableInstalled.setResourceValues(values)
        #endif

        try Data("corrupt-source-after-install".utf8).write(to: compressedURL)
        let reused = try installer.install(
            compressedURL: compressedURL,
            metadataURL: metadataURL,
            cacheDirectory: cacheURL
        )
        XCTAssertEqual(reused, installed)
        XCTAssertEqual(try Data(contentsOf: reused), database)
        #if os(iOS) || os(macOS) || os(watchOS) || os(tvOS) || os(visionOS)
        XCTAssertEqual(try reused.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        #endif

        try compressed.write(to: compressedURL)
        try Data("corrupt-cache".utf8).write(to: installed)
        let replaced = try installer.install(
            compressedURL: compressedURL,
            metadataURL: metadataURL,
            cacheDirectory: cacheURL
        )
        XCTAssertEqual(replaced, installed)
        XCTAssertEqual(try Data(contentsOf: replaced), database)
    }

    func testBundledInstallerFastPathDoesNotTrustASameSizeCorruptCache() throws {
        let fixture = try Fixture()
        let database = try Data(contentsOf: fixture.url)
        let compressed = try zlibCompress(database)
        let temporary = FileManager.default.temporaryDirectory
            .appendingPathComponent("BinarioGTFSFastPathTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: temporary) }
        let compressedURL = temporary.appendingPathComponent("schedule.sqlite.zlib")
        let metadataURL = temporary.appendingPathComponent("schedule.metadata.json")
        let cacheURL = temporary.appendingPathComponent("cache", isDirectory: true)
        try compressed.write(to: compressedURL)
        try JSONSerialization.data(
            withJSONObject: installerMetadata(database: database, compressed: compressed),
            options: [.sortedKeys]
        ).write(to: metadataURL)
        try FileManager.default.createDirectory(at: cacheURL, withIntermediateDirectories: true)
        // Same name and same size as the genuine cache, but not a valid database: the fast
        // path must fall through to a verified reinstall instead of serving it.
        let digest = BundledGTFSScheduleInstaller.sha256Hex(database)
        let destination = cacheURL.appendingPathComponent("binario-gtfs-\(digest.prefix(16)).sqlite", isDirectory: false)
        try Data(repeating: 0x5a, count: database.count).write(to: destination)

        let installed = try BundledGTFSScheduleInstaller().install(
            compressedURL: compressedURL,
            metadataURL: metadataURL,
            cacheDirectory: cacheURL
        )

        XCTAssertEqual(installed, destination)
        XCTAssertEqual(try Data(contentsOf: installed), database)
    }

    func testBundledInstallerRejectsTamperedCompressedData() throws {
        let fixture = try Fixture()
        let database = try Data(contentsOf: fixture.url)
        let compressed = try zlibCompress(database)
        let temporary = FileManager.default.temporaryDirectory
            .appendingPathComponent("BinarioGTFSTamperTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: temporary) }
        let compressedURL = temporary.appendingPathComponent("schedule.sqlite.zlib")
        let metadataURL = temporary.appendingPathComponent("schedule.metadata.json")
        var tampered = compressed
        tampered[tampered.startIndex] ^= 0xff
        try tampered.write(to: compressedURL)
        try JSONSerialization.data(
            withJSONObject: installerMetadata(database: database, compressed: compressed),
            options: [.sortedKeys]
        ).write(to: metadataURL)

        XCTAssertThrowsError(try BundledGTFSScheduleInstaller().install(
            compressedURL: compressedURL,
            metadataURL: metadataURL,
            cacheDirectory: temporary.appendingPathComponent("cache")
        )) { error in
            XCTAssertEqual(error as? BundledGTFSScheduleError, .invalidCompressedArtifact)
        }
    }

    func testResultsAreSortedAndBoundedAtEight() async throws {
        let fixture = try Fixture()
        for index in 0..<12 {
            try fixture.insertTrip(id: Int64(index + 1), externalID: "trip-\(index)", number: "R \(index)", seconds: 10 * 3_600 + (11 - index) * 60)
        }
        let result = await fixture.repository.journeys(for: try query("2024-04-01 09:00"))
        guard case let .available(snapshot) = result else { return XCTFail("Expected bounded services") }
        XCTAssertEqual(snapshot.services.count, SQLiteGTFSJourneyRepository.maximumResults)
        XCTAssertEqual(snapshot.services.first?.scheduledDeparture, "10:00")
        XCTAssertEqual(snapshot.services.last?.scheduledDeparture, "10:07")
    }

    private func query(_ value: String) throws -> JourneyQuery {
        try JourneyQuery(origin: origin, destination: destination, departure: date(value))
    }

    private func date(_ value: String) throws -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = rome
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return try XCTUnwrap(formatter.date(from: value))
    }

    private func installerMetadata(database: Data, compressed: Data) -> [String: Any] {
        [
            "schemaVersion": 1,
            "database": [
                "applicationId": Int(SQLiteGTFSJourneyRepository.applicationID),
                "userVersion": Int(SQLiteGTFSJourneyRepository.userVersion),
                "sha256": BundledGTFSScheduleInstaller.sha256Hex(database),
                "sizeBytes": database.count,
            ],
            "compression": [
                "format": "zlib",
                "level": 9,
                "sha256": BundledGTFSScheduleInstaller.sha256Hex(compressed),
                "sizeBytes": compressed.count,
            ],
            "inputs": [
                "fingerprintSha256": String(repeating: "a", count: 64),
                "sourceGeneratedAt": "2024-03-30",
            ],
            "maximumCompressedSizeBytes": BundledGTFSScheduleInstaller.maximumCompressedBytes,
        ]
    }

    private func zlibCompress(_ data: Data) throws -> Data {
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
        guard status == Z_OK else { throw NSError(domain: "zlib", code: Int(status)) }
        output.count = Int(outputLength)
        return output
    }

    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("BinarioKit-\(UUID().uuidString).sqlite")
    }

    private final class Fixture {
        let url: URL
        let repository: SQLiteGTFSJourneyRepository
        private var database: OpaquePointer?

        init(activeDates: [Int] = [20240401]) throws {
            url = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioKit-\(UUID().uuidString).sqlite")
            repository = SQLiteGTFSJourneyRepository(databaseURL: url)
            guard sqlite3_open(url.path, &database) == SQLITE_OK else { throw FixtureError.sqlite }
            try execute(Self.schema)
            try execute("PRAGMA application_id = \(SQLiteGTFSJourneyRepository.applicationID); PRAGMA user_version = 1;")
            let bits = Self.bitset(startDate: 20240330, dayCount: 4, activeDates: activeDates)
            try execute("""
                INSERT INTO metadata VALUES ('schema_version', '1');
                INSERT INTO metadata VALUES ('input_fingerprint_sha256', '\(String(repeating: "a", count: 64))');
                INSERT INTO metadata VALUES ('source_generated_at', '2024-03-30');
                INSERT INTO metadata VALUES ('row_counts_json', '{}');
                INSERT INTO pack VALUES (1, 'source', 'Synthetic official feed', 20241231);
                INSERT INTO agency VALUES (1, 1, 'agency', 'Synthetic Rail', 'Europe/Rome');
                INSERT INTO route VALUES (1, 1, 1, 'route', 'Rail', 'Roma-Milano', 2);
                INSERT INTO service VALUES (1, 1, 'daily', 20240330, 4, X'\(bits)');
                INSERT INTO stop VALUES (1, 1, 'origin', 'Roma Termini', NULL, NULL, 'catalog-origin', 'RM', NULL);
                INSERT INTO stop VALUES (2, 1, 'destination', 'Milano Centrale', NULL, NULL, 'catalog-destination', 'MI', NULL);
                """)
        }

        deinit {
            if let database { sqlite3_close(database) }
            try? FileManager.default.removeItem(at: url)
        }

        func insertTrip(
            id: Int64,
            externalID: String,
            number: String = "R 1",
            originSequence: Int = 1,
            destinationSequence: Int = 2,
            seconds: Int,
            pickupType: Int = 0,
            dropOffType: Int = 0
        ) throws {
            try execute("""
                INSERT INTO trip VALUES (\(id), 1, 1, 1, '\(externalID)', NULL, '\(number)', NULL);
                INSERT INTO stop_time VALUES (\(id), \(originSequence), 1, \(seconds), \(seconds), \(pickupType), 0);
                INSERT INTO stop_time VALUES (\(id), \(destinationSequence), 2, \(seconds + 3600), \(seconds + 3600), 0, \(dropOffType));
                """)
        }

        func execute(_ sql: String) throws {
            guard let database, sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else { throw FixtureError.sqlite }
        }

        private static func bitset(startDate: Int, dayCount: Int, activeDates: [Int]) -> String {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(identifier: "Europe/Rome")
            formatter.dateFormat = "yyyyMMdd"
            let start = formatter.date(from: String(startDate))!
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = formatter.timeZone!
            var byte: UInt8 = 0
            for encoded in activeDates {
                let date = formatter.date(from: String(encoded))!
                let offset = calendar.dateComponents([.day], from: start, to: date).day!
                if offset >= 0 && offset < dayCount { byte |= UInt8(1 << offset) }
            }
            return String(format: "%02X", byte)
        }

        private static let schema = """
            CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID;
            CREATE TABLE pack (id INTEGER PRIMARY KEY, source_id TEXT NOT NULL UNIQUE, attribution TEXT NOT NULL, last_service_date INTEGER NOT NULL);
            CREATE TABLE agency (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, name TEXT NOT NULL, timezone TEXT NOT NULL, UNIQUE(pack_id, external_id));
            CREATE TABLE route (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), agency_id INTEGER NOT NULL REFERENCES agency(id), external_id TEXT NOT NULL, short_name TEXT, long_name TEXT, route_type INTEGER NOT NULL, UNIQUE(pack_id, external_id));
            CREATE TABLE service (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, start_date INTEGER NOT NULL, day_count INTEGER NOT NULL CHECK(day_count > 0), active_days BLOB NOT NULL, UNIQUE(pack_id, external_id), CHECK(length(active_days) = (day_count + 7) / 8));
            CREATE TABLE trip (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), route_id INTEGER NOT NULL REFERENCES route(id), service_id INTEGER NOT NULL REFERENCES service(id), external_id TEXT NOT NULL, headsign TEXT, short_name TEXT, direction_id INTEGER, UNIQUE(pack_id, external_id));
            CREATE TABLE stop (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, name TEXT NOT NULL, latitude REAL, longitude REAL, catalog_station_id TEXT, railway_code TEXT, trenitalia_location_id TEXT, UNIQUE(pack_id, external_id));
            CREATE TABLE stop_time (trip_id INTEGER NOT NULL REFERENCES trip(id), stop_sequence INTEGER NOT NULL, stop_id INTEGER NOT NULL REFERENCES stop(id), arrival_seconds INTEGER, departure_seconds INTEGER, pickup_type INTEGER NOT NULL, drop_off_type INTEGER NOT NULL, PRIMARY KEY(trip_id, stop_sequence)) WITHOUT ROWID;
            CREATE UNIQUE INDEX idx_route_pack_external ON route(pack_id, external_id);
            CREATE UNIQUE INDEX idx_service_pack_external ON service(pack_id, external_id);
            CREATE UNIQUE INDEX idx_trip_pack_external ON trip(pack_id, external_id);
            CREATE INDEX idx_trip_route_service ON trip(route_id, service_id);
            CREATE UNIQUE INDEX idx_stop_pack_external ON stop(pack_id, external_id);
            CREATE INDEX idx_stop_catalog_station ON stop(catalog_station_id) WHERE catalog_station_id IS NOT NULL;
            CREATE INDEX idx_stop_time_stop_departure ON stop_time(stop_id, departure_seconds, trip_id);
            CREATE INDEX idx_stop_time_trip_sequence ON stop_time(trip_id, stop_sequence);
            """
    }

    private enum FixtureError: Error { case sqlite }
}
