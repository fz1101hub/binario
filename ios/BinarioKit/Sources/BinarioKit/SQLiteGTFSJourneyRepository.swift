import CSQLite
import Foundation

/// Read-only access to the versioned Binario static-GTFS SQLite artifact.
/// Invalid, corrupt, ambiguous, or route-less databases fail closed.
public struct SQLiteGTFSJourneyRepository: JourneyRepository, Sendable {
    public static let applicationID: Int32 = 0x424E_4754 // "BNGT"
    public static let userVersion: Int32 = 1
    public static let maximumResults = 8

    private static let expectedTables: [String: [String]] = [
        "metadata": ["key", "value"],
        "pack": ["id", "source_id", "attribution", "last_service_date"],
        "agency": ["id", "pack_id", "external_id", "name", "timezone"],
        "route": ["id", "pack_id", "agency_id", "external_id", "short_name", "long_name", "route_type"],
        "service": ["id", "pack_id", "external_id", "start_date", "day_count", "active_days"],
        "trip": ["id", "pack_id", "route_id", "service_id", "external_id", "headsign", "short_name", "direction_id"],
        "stop": ["id", "pack_id", "external_id", "name", "latitude", "longitude", "catalog_station_id", "railway_code", "trenitalia_location_id"],
        "stop_time": ["trip_id", "stop_sequence", "stop_id", "arrival_seconds", "departure_seconds", "pickup_type", "drop_off_type"],
    ]
    private static let expectedIndices: Set<String> = [
        "idx_route_pack_external", "idx_service_pack_external", "idx_trip_pack_external",
        "idx_trip_route_service", "idx_stop_pack_external", "idx_stop_catalog_station",
        "idx_stop_time_stop_departure", "idx_stop_time_trip_sequence",
    ]
    private static let maximumResolvedStops = 128
    private static let maximumTimeZones = 16
    private static let maximumCandidateRows = 4_096
    private static let maximumGTFSSeconds = 168 * 60 * 60 - 1
    private static let maximumServiceDayLookback = maximumGTFSSeconds / (24 * 60 * 60)
    private static let maximumServiceDays: Int64 = 3_660
    private static let maximumSourceLabelLength = 96

    public let databaseURL: URL

    public init(databaseURL: URL) {
        self.databaseURL = databaseURL
    }

    static func validatesDatabase(at url: URL, inputFingerprint: String) -> Bool {
        do {
            let metadata = try validate(SQLiteDatabase(readOnlyURL: url))
            return metadata["input_fingerprint_sha256"] == inputFingerprint
        } catch {
            return false
        }
    }

    public func journeys(for query: JourneyQuery) async -> JourneyResult {
        do {
            return .available(try Self.load(databaseURL: databaseURL, query: query))
        } catch SQLiteGTFSFailure.noRoute {
            return .unavailable(.noJourney)
        } catch {
            return .unavailable(.providerInvalid)
        }
    }

    private static func load(databaseURL: URL, query: JourneyQuery) throws -> JourneySnapshot {
        let database = try SQLiteDatabase(readOnlyURL: databaseURL)
        let metadata = try validate(database)
        guard let sourceGeneratedAt = metadata["source_generated_at"],
              let sourceObservedAt = parseSourceDate(sourceGeneratedAt)
        else { throw SQLiteGTFSFailure.invalidData }
        let originIDs = try resolve(query.origin, database: database)
        let destinationIDs = try resolve(query.destination, database: database)
        guard Set(originIDs).isDisjoint(with: destinationIDs) else { throw SQLiteGTFSFailure.noRoute }

        let timeZones = try agencyTimeZones(database)
        var candidates: [Candidate] = []
        for timeZone in timeZones {
            let calendar = serviceCalendar(timeZone: timeZone)
            let requestedDay = calendar.startOfDay(for: query.departure)
            for offset in -maximumServiceDayLookback...1 {
                guard let serviceDay = calendar.date(byAdding: .day, value: offset, to: requestedDay) else {
                    throw SQLiteGTFSFailure.invalidData
                }
                candidates += try queryCandidates(
                    database: database,
                    originIDs: originIDs,
                    destinationIDs: destinationIDs,
                    agencyTimeZone: timeZone.identifier,
                    serviceDay: serviceDay,
                    calendar: calendar
                )
            }
        }

        let upperBound = query.departure.addingTimeInterval(24 * 60 * 60)
        let selected = candidates
            .filter { $0.departure >= query.departure && $0.departure <= upperBound }
            .sorted {
                if $0.departure != $1.departure { return $0.departure < $1.departure }
                if $0.number != $1.number { return $0.number < $1.number }
                return $0.id < $1.id
            }
            .reduce(into: [Candidate]()) { result, candidate in
                guard result.count < maximumResults else { return }
                guard !result.contains(where: { $0.id == candidate.id }) else { return }
                result.append(candidate)
            }
        guard !selected.isEmpty else { throw SQLiteGTFSFailure.noRoute }

        let sourceLabel = boundedSourceLabel(selected.map(\.attribution).uniqued())
        guard !sourceLabel.isEmpty else { throw SQLiteGTFSFailure.invalidData }
        let generatedAt = Date()
        return JourneySnapshot(
            query: query,
            generatedAt: generatedAt,
            expiresAt: generatedAt.addingTimeInterval(12 * 60 * 60),
            operationalState: .programmed,
            provenance: DataProvenance(
                producer: .iosApp,
                nature: .programmed,
                sourceLabel: sourceLabel,
                observedAt: sourceObservedAt,
                freshness: .unknown,
                confidence: .staticOnly
            ),
            services: selected.map(\.service)
        )
    }

    private static func validate(_ database: SQLiteDatabase) throws -> [String: String] {
        guard try database.scalarInt("PRAGMA query_only") == 1 else { throw SQLiteGTFSFailure.invalidDatabase }
        guard try database.scalarInt("PRAGMA application_id") == Int64(applicationID) else {
            throw SQLiteGTFSFailure.invalidDatabase
        }
        guard try database.scalarInt("PRAGMA user_version") == Int64(userVersion) else {
            throw SQLiteGTFSFailure.invalidDatabase
        }
        guard try database.scalarText("PRAGMA quick_check(1)") == "ok" else {
            throw SQLiteGTFSFailure.invalidDatabase
        }

        let tables = try database.names(type: "table", excludingAutoIndices: false)
        guard tables == Set(expectedTables.keys) else { throw SQLiteGTFSFailure.invalidSchema }
        for (table, columns) in expectedTables {
            guard try database.columns(in: table) == columns else { throw SQLiteGTFSFailure.invalidSchema }
        }
        let indices = try database.names(type: "index", excludingAutoIndices: true)
        guard indices == expectedIndices else { throw SQLiteGTFSFailure.invalidSchema }

        let metadata = try database.keyValues("SELECT key, value FROM metadata")
        guard metadata["schema_version"] == String(userVersion) else {
            throw SQLiteGTFSFailure.invalidSchema
        }
        guard metadata["input_fingerprint_sha256"]?.count == 64 else {
            throw SQLiteGTFSFailure.invalidSchema
        }
        guard let sourceGeneratedAt = metadata["source_generated_at"],
              parseSourceDate(sourceGeneratedAt) != nil
        else { throw SQLiteGTFSFailure.invalidSchema }
        guard metadata["row_counts_json"]?.nonEmpty != nil else {
            throw SQLiteGTFSFailure.invalidSchema
        }
        return metadata
    }

    private static func agencyTimeZones(_ database: SQLiteDatabase) throws -> [TimeZone] {
        let statement = try database.statement("SELECT DISTINCT timezone FROM agency ORDER BY timezone LIMIT ?")
        try statement.bind(Int64(maximumTimeZones + 1), at: 1)
        var zones: [TimeZone] = []
        while try statement.step() {
            guard let identifier = statement.text(at: 0), let zone = TimeZone(identifier: identifier) else {
                throw SQLiteGTFSFailure.invalidData
            }
            zones.append(zone)
        }
        guard !zones.isEmpty, zones.count <= maximumTimeZones else { throw SQLiteGTFSFailure.limitExceeded }
        return zones
    }

    private static func resolve(
        _ identity: StationIdentity,
        database: SQLiteDatabase
    ) throws -> [Int64] {
        let statement = try database.statement(
            "SELECT id, railway_code FROM stop WHERE catalog_station_id = ? ORDER BY id LIMIT ?"
        )
        try statement.bind(identity.id, at: 1)
        try statement.bind(Int64(maximumResolvedStops + 1), at: 2)
        var matches: [(id: Int64, railwayCode: String?)] = []
        while try statement.step() {
            matches.append((statement.int(at: 0), statement.text(at: 1)))
            guard matches.count <= maximumResolvedStops else { throw SQLiteGTFSFailure.limitExceeded }
        }
        guard !matches.isEmpty else { throw SQLiteGTFSFailure.noRoute }

        if let wantedCode = identity.railwayCode {
            let exactCode = matches.filter { $0.railwayCode == wantedCode }
            guard !exactCode.isEmpty else { throw SQLiteGTFSFailure.noRoute }
            return exactCode.map(\.id)
        }
        return matches.map(\.id)
    }

    private static func queryCandidates(
        database: SQLiteDatabase,
        originIDs: [Int64],
        destinationIDs: [Int64],
        agencyTimeZone: String,
        serviceDay: Date,
        calendar: Calendar
    ) throws -> [Candidate] {
        let originPlaceholders = Array(repeating: "?", count: originIDs.count).joined(separator: ",")
        let destinationPlaceholders = Array(repeating: "?", count: destinationIDs.count).joined(separator: ",")
        let sql = """
            SELECT t.id, t.external_id, t.short_name, t.headsign,
                   r.short_name, r.long_name, r.route_type,
                   a.name, a.timezone, destination_stop.name,
                   COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds),
                   p.source_id, p.attribution,
                   svc.start_date, svc.day_count, svc.active_days
            FROM stop_time AS origin_time
            JOIN stop_time AS destination_time
              ON destination_time.trip_id = origin_time.trip_id
             AND destination_time.stop_sequence > origin_time.stop_sequence
            JOIN stop AS destination_stop ON destination_stop.id = destination_time.stop_id
            JOIN trip AS t ON t.id = origin_time.trip_id
            JOIN route AS r ON r.id = t.route_id
            JOIN agency AS a ON a.id = r.agency_id
            JOIN pack AS p ON p.id = t.pack_id
            JOIN service AS svc ON svc.id = t.service_id
            WHERE origin_time.stop_id IN (\(originPlaceholders))
              AND destination_time.stop_id IN (\(destinationPlaceholders))
              AND origin_time.pickup_type = 0
              AND destination_time.drop_off_type = 0
              AND a.timezone = ?
              AND COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds) BETWEEN 0 AND ?
            ORDER BY COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds), t.id,
                     destination_time.stop_sequence
            LIMIT ?
            """
        let statement = try database.statement(sql)
        var binding: Int32 = 1
        for id in originIDs { try statement.bind(id, at: binding); binding += 1 }
        for id in destinationIDs { try statement.bind(id, at: binding); binding += 1 }
        try statement.bind(agencyTimeZone, at: binding); binding += 1
        try statement.bind(Int64(maximumGTFSSeconds), at: binding); binding += 1
        try statement.bind(Int64(maximumCandidateRows + 1), at: binding)

        var candidates: [Candidate] = []
        while try statement.step() {
            guard candidates.count < maximumCandidateRows else { throw SQLiteGTFSFailure.limitExceeded }
            guard
                let externalTripID = statement.text(at: 1), !externalTripID.isEmpty,
                let agencyName = statement.text(at: 7), !agencyName.isEmpty,
                statement.text(at: 8) == agencyTimeZone,
                let destinationName = statement.text(at: 9), !destinationName.isEmpty,
                let sourceID = statement.text(at: 11), !sourceID.isEmpty,
                let attribution = statement.text(at: 12)?.normalizedWhitespace, !attribution.isEmpty,
                let activeDays = statement.blob(at: 15)
            else { throw SQLiteGTFSFailure.invalidData }
            let seconds = statement.int(at: 10)
            let startDate = statement.int(at: 13)
            let dayCount = statement.int(at: 14)
            guard
                seconds >= 0, seconds <= Int64(maximumGTFSSeconds),
                serviceRuns(serviceDay: serviceDay, startDate: startDate, dayCount: dayCount,
                            activeDays: activeDays, calendar: calendar)
            else { continue }

            let departure = try gtfsDate(serviceDay: serviceDay, seconds: TimeInterval(seconds), calendar: calendar)
            let rawNumber = statement.text(at: 2)?.nonEmpty
                ?? statement.text(at: 4)?.nonEmpty
                ?? externalTripID
            let rawCategory = statement.text(at: 4)?.nonEmpty
                ?? statement.text(at: 5)?.nonEmpty
                ?? "GTFS \(statement.int(at: 6))"
            let number = bounded(rawNumber, maximum: 24)
            let operatorName = bounded(agencyName, maximum: 64)
            let category = bounded(rawCategory, maximum: 64)
            let destination = bounded(destinationName, maximum: 96)
            guard !number.isEmpty, !operatorName.isEmpty, !category.isEmpty, !destination.isEmpty else {
                throw SQLiteGTFSFailure.invalidData
            }
            let formattedTime = timeString(departure, timeZone: calendar.timeZone)
            let serviceDate = encodedDate(serviceDay, calendar: calendar)
            let id = "\(sourceID):\(externalTripID):\(serviceDate):\(seconds)"
            candidates.append(Candidate(
                id: id,
                number: number,
                departure: departure,
                attribution: attribution,
                service: TrainService(
                    id: id,
                    number: number,
                    operatorName: operatorName,
                    category: category,
                    destination: destination,
                    scheduledDeparture: formattedTime,
                    expectedDeparture: formattedTime,
                    status: OperationalState.programmed.rawValue,
                    platform: nil,
                    delayMinutes: 0
                )
            ))
        }
        return candidates
    }

    private static func serviceRuns(
        serviceDay: Date,
        startDate: Int64,
        dayCount: Int64,
        activeDays: Data,
        calendar: Calendar
    ) -> Bool {
        guard dayCount > 0, dayCount <= maximumServiceDays,
              activeDays.count == (Int(dayCount) + 7) / 8,
              let start = date(from: startDate, calendar: calendar),
              let offset = calendar.dateComponents([.day], from: start, to: serviceDay).day,
              offset >= 0, Int64(offset) < dayCount else { return false }
        return activeDays[offset / 8] & UInt8(1 << (offset % 8)) != 0
    }

    private static func date(from encoded: Int64, calendar: Calendar) -> Date? {
        guard encoded >= 1_000_0000, encoded <= 9_999_1231 else { return nil }
        var components = DateComponents()
        components.calendar = calendar
        components.timeZone = calendar.timeZone
        components.year = Int(encoded / 10_000)
        components.month = Int(encoded / 100 % 100)
        components.day = Int(encoded % 100)
        components.hour = 0
        return calendar.date(from: components)
    }

    private static func encodedDate(_ date: Date, calendar: Calendar) -> Int {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return (parts.year ?? 0) * 10_000 + (parts.month ?? 0) * 100 + (parts.day ?? 0)
    }

    /// GTFS time is elapsed time from local noon minus twelve hours. Adding the
    /// duration to that instant correctly traverses 23/25-hour DST service days.
    private static func gtfsDate(serviceDay: Date, seconds: TimeInterval, calendar: Calendar) throws -> Date {
        var noon = calendar.dateComponents([.year, .month, .day], from: serviceDay)
        noon.hour = 12
        noon.minute = 0
        noon.second = 0
        guard let localNoon = calendar.date(from: noon) else { throw SQLiteGTFSFailure.invalidData }
        return localNoon.addingTimeInterval(seconds - 12 * 60 * 60)
    }

    private static func serviceCalendar(timeZone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = timeZone
        return calendar
    }

    private static func timeString(_ date: Date, timeZone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = serviceCalendar(timeZone: timeZone)
        formatter.timeZone = timeZone
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    private static func parseSourceDate(_ value: String) -> Date? {
        let parts = value.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let year = Int(parts[0]),
              let month = Int(parts[1]),
              let day = Int(parts[2])
        else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar.date(from: DateComponents(year: year, month: month, day: day))
    }

    private static func bounded(_ value: String, maximum: Int) -> String {
        let normalized = value.normalizedWhitespace
        guard normalized.count > maximum else { return normalized }
        return String(normalized.prefix(maximum - 1)) + "…"
    }

    private static func boundedSourceLabel(_ labels: [String]) -> String {
        let joined = labels.map { String($0.prefix(96)) }.joined(separator: " + ")
        guard joined.count > maximumSourceLabelLength else { return joined }
        return String(joined.prefix(maximumSourceLabelLength - 1)) + "…"
    }

    private struct Candidate {
        let id: String
        let number: String
        let departure: Date
        let attribution: String
        let service: TrainService
    }
}

private enum SQLiteGTFSFailure: Error {
    case invalidDatabase, invalidSchema, invalidData, limitExceeded, noRoute
}

private final class SQLiteDatabase {
    private var handle: OpaquePointer?

    init(readOnlyURL: URL) throws {
        guard readOnlyURL.isFileURL else { throw SQLiteGTFSFailure.invalidDatabase }
        let flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_URI | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(readOnlyURL.absoluteString, &handle, flags, nil) == SQLITE_OK, let handle else {
            if let handle { sqlite3_close_v2(handle) }
            self.handle = nil
            throw SQLiteGTFSFailure.invalidDatabase
        }
        sqlite3_extended_result_codes(handle, 1)
        guard sqlite3_db_readonly(handle, "main") == 1 else { throw SQLiteGTFSFailure.invalidDatabase }
        try execute("PRAGMA query_only = ON")
    }

    deinit { if let handle { sqlite3_close_v2(handle) } }

    func execute(_ sql: String) throws {
        guard let handle, sqlite3_exec(handle, sql, nil, nil, nil) == SQLITE_OK else {
            throw SQLiteGTFSFailure.invalidDatabase
        }
    }

    func statement(_ sql: String) throws -> SQLiteStatement {
        guard let handle else { throw SQLiteGTFSFailure.invalidDatabase }
        return try SQLiteStatement(database: handle, sql: sql)
    }

    func scalarInt(_ sql: String) throws -> Int64 {
        let statement = try statement(sql)
        guard try statement.step() else { throw SQLiteGTFSFailure.invalidDatabase }
        return statement.int(at: 0)
    }

    func scalarText(_ sql: String) throws -> String? {
        let statement = try statement(sql)
        guard try statement.step() else { throw SQLiteGTFSFailure.invalidDatabase }
        return statement.text(at: 0)
    }

    func columns(in table: String) throws -> [String] {
        let statement = try statement("PRAGMA table_info(\(table))")
        var columns: [String] = []
        while try statement.step() {
            guard let name = statement.text(at: 1) else { throw SQLiteGTFSFailure.invalidSchema }
            columns.append(name)
        }
        return columns
    }

    func names(type: String, excludingAutoIndices: Bool) throws -> Set<String> {
        let suffix = excludingAutoIndices ? " AND name NOT LIKE 'sqlite_autoindex_%'" : ""
        let statement = try statement("SELECT name FROM sqlite_master WHERE type = ?\(suffix)")
        try statement.bind(type, at: 1)
        var names = Set<String>()
        while try statement.step() {
            guard let name = statement.text(at: 0) else { throw SQLiteGTFSFailure.invalidSchema }
            names.insert(name)
        }
        return names
    }

    func keyValues(_ sql: String) throws -> [String: String] {
        let statement = try statement(sql)
        var values: [String: String] = [:]
        while try statement.step() {
            guard let key = statement.text(at: 0), let value = statement.text(at: 1), values[key] == nil else {
                throw SQLiteGTFSFailure.invalidSchema
            }
            values[key] = value
        }
        return values
    }
}

private final class SQLiteStatement {
    private var handle: OpaquePointer?

    init(database: OpaquePointer, sql: String) throws {
        guard sqlite3_prepare_v2(database, sql, -1, &handle, nil) == SQLITE_OK, handle != nil else {
            throw SQLiteGTFSFailure.invalidDatabase
        }
    }

    deinit { if let handle { sqlite3_finalize(handle) } }

    func bind(_ value: String, at index: Int32) throws {
        guard let handle, sqlite3_bind_text(handle, index, value, -1, binario_sqlite_transient()) == SQLITE_OK else {
            throw SQLiteGTFSFailure.invalidDatabase
        }
    }

    func bind(_ value: Int64, at index: Int32) throws {
        guard let handle, sqlite3_bind_int64(handle, index, value) == SQLITE_OK else {
            throw SQLiteGTFSFailure.invalidDatabase
        }
    }

    func step() throws -> Bool {
        guard let handle else { throw SQLiteGTFSFailure.invalidDatabase }
        switch sqlite3_step(handle) {
        case SQLITE_ROW: return true
        case SQLITE_DONE: return false
        default: throw SQLiteGTFSFailure.invalidDatabase
        }
    }

    func int(at index: Int32) -> Int64 {
        guard let handle else { return 0 }
        return sqlite3_column_int64(handle, index)
    }

    func text(at index: Int32) -> String? {
        guard let handle, sqlite3_column_type(handle, index) != SQLITE_NULL,
              let value = sqlite3_column_text(handle, index) else { return nil }
        return String(cString: value)
    }

    func blob(at index: Int32) -> Data? {
        guard let handle, sqlite3_column_type(handle, index) != SQLITE_NULL else { return nil }
        let count = Int(sqlite3_column_bytes(handle, index))
        guard count >= 0 else { return nil }
        if count == 0 { return Data() }
        guard let bytes = sqlite3_column_blob(handle, index) else { return nil }
        return Data(bytes: bytes, count: count)
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
    var normalizedWhitespace: String { split(whereSeparator: \.isWhitespace).joined(separator: " ") }
}

private extension Sequence where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
