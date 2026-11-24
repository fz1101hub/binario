import CSQLite
import Foundation

// A GTFS archive downloaded at runtime (remote-gtfs-v1.json: European Sleeper's community mirror), read on the iPhone
// and turned into a small timetable index with the schema of the bundled one (tools/build_ios_gtfs_schedule.py,
// `userVersion` 2), so that the planner and «Partenze» read it with their own queries
// (`SQLiteGTFSJourneyRepository`) instead of a second planner. As on Android (`RemoteGtfsFallbackDataSource`), only the
// trains of the feed's operator are kept (`operatorScope`); a stop is bound to the catalogue station whose name, or
// a spelling of it, is the stop's name (`RemoteGTFSStationMatch`, Android's `gtfsCandidateNames`). The cases both
// platforms read are contracts/shared-rules/v1/remote-gtfs-v1.json.

/// Why a downloaded archive gave no index.
public enum RemoteGTFSIndexFailure: Error, Equatable, Sendable {
    case archive(GTFSArchiveFailure)
    /// A table over its row or line limit (`SharedData.RemoteGtfs.archive`).
    case limitExceeded
    /// A required column is absent.
    case invalidFeed
    /// The feed has no train of its operator.
    case noTrains
    case storage
}

/// The catalogue station a stop of a downloaded archive is bound to. Android's route search reads the stop names of
/// the feed and finds a station when one of its candidate names (its name, and the aliases that share a word of four
/// letters or more with it: never a translation such as «Mailand») is the stop's name, letters and digits only,
/// without accents and case. One station so named is bound; of several, the one nearest the stop when both have
/// coordinates, else the one the catalogue prefers (a Trenitalia location id, then a railway code, then the
/// identifier), as the bundled index collapses co-located duplicates.
public struct RemoteGTFSStationMatch: Sendable {
    private let byName: [String: [Station]]

    public init(stations: [Station]) {
        var byName: [String: [Station]] = [:]
        for station in stations {
            for name in Self.candidateNames(station) {
                byName[Self.normalize(name), default: []].append(station)
            }
        }
        self.byName = byName
    }

    /// The station the stop `name` (at `latitude`, `longitude` when the feed gives them) is bound to, if any.
    public func station(stopName name: String, latitude: Double?, longitude: Double?) -> Station? {
        let key = Self.normalize(name)
        guard !key.isEmpty, let named = byName[key], !named.isEmpty else { return nil }
        var unique: [Station] = []
        for station in named where !unique.contains(where: { $0.id == station.id }) { unique.append(station) }
        if unique.count == 1 { return unique[0] }
        if let latitude, let longitude {
            var nearest: (station: Station, kilometres: Double)?
            for station in unique {
                guard let lat = station.latitude, let lon = station.longitude else { continue }
                let distance = Self.kilometres(latitude, longitude, lat, lon)
                if let kept = nearest, kept.kilometres < distance || (kept.kilometres == distance && kept.station.id < station.id) {
                    continue
                }
                nearest = (station, distance)
            }
            if let nearest { return nearest.station }
        }
        return unique.min { Self.preference($0) < Self.preference($1) }
    }

    /// Android's `EmbeddedGtfsRouteAdapter.normalize`: no accents, lower case, letters and digits only.
    public static func normalize(_ value: String) -> String {
        var result = String.UnicodeScalarView()
        for scalar in value.decomposedStringWithCanonicalMapping.lowercased().unicodeScalars
        where ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) {
            result.append(scalar)
        }
        return String(result)
    }

    /// Android's `gtfsCandidateNames`: the name, and the aliases that are other spellings of it (a word of four letters
    /// or more in common, no code-like alias), each once.
    public static func candidateNames(_ station: Station) -> [String] {
        let nameWords = Set(RailStopNameMatcher.tokens(station.name).filter { $0.count >= 4 })
        let spellings = station.aliases.filter { alias in
            !alias.contains("_") && !alias.hasPrefix("@") && normalize(alias).count >= 3
                && RailStopNameMatcher.tokens(alias).contains { nameWords.contains($0) }
        }
        var names: [String] = []
        var seen = Set<String>()
        for candidate in [station.name] + spellings where seen.insert(normalize(candidate)).inserted {
            names.append(candidate)
        }
        return names
    }

    private static func preference(_ station: Station) -> (Int, Int, String) {
        (station.trenitaliaLocationId == nil ? 1 : 0, station.railwayCode == nil ? 1 : 0, station.id)
    }

    private static func kilometres(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let radians = Double.pi / 180
        let dLat = (lat2 - lat1) * radians
        let dLon = (lon2 - lon1) * radians
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * radians) * cos(lat2 * radians) * sin(dLon / 2) * sin(dLon / 2)
        return 6_371 * 2 * atan2(a.squareRoot(), (1 - a).squareRoot())
    }
}

/// Builds the index of one downloaded archive.
public enum RemoteGTFSIndex {
    /// The trains of the feed's operator, as an index at `destination` (replaced when present). `generatedOn` is the
    /// day the archive was downloaded (`source_generated_at`).
    public static func build(
        archive data: Data,
        feed: SharedData.RemoteGtfs.RemoteFeed,
        stations: [Station],
        generatedOn: Date,
        into destination: URL
    ) throws {
        let archive: GTFSZipArchive
        do {
            archive = try GTFSZipArchive(data)
            try archive.requireGTFSFiles()
        } catch let failure as GTFSArchiveFailure {
            throw RemoteGTFSIndexFailure.archive(failure)
        }
        let feedData = try RemoteFeedData.read(archive, operatorScope: feed.operatorScope, sourceID: feed.pack)
        try RemoteIndexWriter.write(
            feedData, feed: feed, match: RemoteGTFSStationMatch(stations: stations), fingerprint: SHA256Digest.hex(data),
            generatedOn: generatedOn, into: destination
        )
    }

    /// GTFS seconds of "H:MM:SS" («25:10:00» after midnight), nil when empty or unreadable (Android's
    /// `gtfsTimeToMinutes`: any hour, minutes 0–59), or past the week the index can hold.
    static func seconds(_ value: String) -> Int? {
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2 || parts.count == 3,
              let hours = Int(parts[0]), hours >= 0, hours < 168,
              parts[1].count == 2, let minutes = Int(parts[1]), (0...59).contains(minutes) else { return nil }
        var seconds = 0
        if parts.count == 3 {
            guard parts[2].count == 2, let value = Int(parts[2]), (0...59).contains(value) else { return nil }
            seconds = value
        }
        return hours * 3_600 + minutes * 60 + seconds
    }
}

// MARK: - Reading the feed

/// The rows of a GTFS table, read as Android's `GtfsCsvRows`: lines split at «\n» («\r» dropped), blank lines
/// skipped, fields split at commas outside double quotes («""» a quote), a UTF-8 byte order mark ignored, values
/// trimmed. A line longer than the table's limit, or more rows than the table may have, refuses the archive.
struct GTFSCSVTable {
    let header: [String]
    private let lines: [String]

    init(_ data: Data, maximumRows: Int, maximumLineLength: Int = SharedData.RemoteGtfs.archive.maximumCsvLineLength) throws {
        var text = String(decoding: data, as: UTF8.self)
        if text.unicodeScalars.first == "\u{FEFF}" { text.unicodeScalars.removeFirst() }
        // «\r\n» is one Character in Swift: the carriage returns go first, as scalars, as Android drops them.
        text.unicodeScalars.removeAll { $0 == "\r" }
        var lines: [String] = []
        var header: [String]?
        for raw in text.unicodeScalars.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(String.UnicodeScalarView(raw))
            guard line.utf16.count <= maximumLineLength else { throw RemoteGTFSIndexFailure.limitExceeded }
            if header == nil {
                header = Self.fields(line).map { $0.trimmingCharacters(in: .whitespaces) }
                continue
            }
            if line.allSatisfy({ $0.isWhitespace }) { continue }
            lines.append(line)
            guard lines.count <= maximumRows else { throw RemoteGTFSIndexFailure.limitExceeded }
        }
        self.header = header ?? []
        self.lines = lines
    }

    func column(_ name: String) -> Int? {
        header.firstIndex(of: name)
    }

    /// Every row's fields, trimmed (Android's `gtfsValue`).
    func forEachRow(_ body: ([String]) throws -> Void) rethrows {
        for line in lines {
            try body(Self.fields(line).map { $0.trimmingCharacters(in: .whitespaces) })
        }
    }

    /// Android's `parseGtfsCsvLine`.
    static func fields(_ line: String) -> [String] {
        guard line.unicodeScalars.contains("\"") else {
            return line.unicodeScalars.split(separator: ",", omittingEmptySubsequences: false)
                .map { String(String.UnicodeScalarView($0)) }
        }
        var values: [String] = []
        var current = String.UnicodeScalarView()
        var quoted = false
        let scalars = Array(line.unicodeScalars)
        var index = 0
        while index < scalars.count {
            let scalar = scalars[index]
            if scalar == "\"" {
                if quoted, index + 1 < scalars.count, scalars[index + 1] == "\"" {
                    current.append("\"")
                    index += 1
                } else {
                    quoted.toggle()
                }
            } else if scalar == ",", !quoted {
                values.append(String(current))
                current = String.UnicodeScalarView()
            } else {
                current.append(scalar)
            }
            index += 1
        }
        values.append(String(current))
        return values
    }
}

private extension Array where Element == String {
    func value(_ index: Int?) -> String {
        guard let index, index >= 0, index < count else { return "" }
        return self[index]
    }
}

/// What the index keeps of a feed: the operator's train (and bus) routes, their trips with at least two stops in
/// time order, the services they run on and the stops they call at.
struct RemoteFeedData {
    struct Agency { let externalID: String; let name: String; let timezone: String }
    struct Route { let externalID: String; let agency: String; let shortName: String; let longName: String; let type: Int }
    struct Trip {
        let externalID: String
        let route: String
        let service: String
        let headsign: String
        let shortName: String
        let direction: Int?
        let bikes: Int?
    }
    struct Call { let sequence: Int; let stop: String; let arrival: Int?; let departure: Int?; let pickup: Int; let dropOff: Int }
    struct Stop { let externalID: String; let name: String; let latitude: Double?; let longitude: Double? }
    struct Service { let externalID: String; let startDate: Int; let dayCount: Int; let activeDays: [UInt8] }

    var agencies: [Agency] = []
    var routes: [Route] = []
    var trips: [Trip] = []
    var calls: [String: [Call]] = [:]
    var stops: [Stop] = []
    var services: [Service] = []

    static let maximumServiceDays = 3_660

    static func read(_ archive: GTFSZipArchive, operatorScope: String, sourceID: String) throws -> RemoteFeedData {
        let limits = SharedData.RemoteGtfs.archive
        func table(_ name: String, rows: Int) throws -> GTFSCSVTable? {
            do {
                guard let data = try archive.data(of: name) else { return nil }
                return try GTFSCSVTable(data, maximumRows: rows, maximumLineLength: limits.maximumCsvLineLength)
            } catch let failure as GTFSArchiveFailure {
                throw RemoteGTFSIndexFailure.archive(failure)
            }
        }
        var feed = RemoteFeedData()

        // Agencies of the operator. Android reads the times in Italian time when the feed names no valid zone.
        guard let agencyTable = try table("agency.txt", rows: limits.maximumAgencies),
              let agencyName = agencyTable.column("agency_name") else { throw RemoteGTFSIndexFailure.invalidFeed }
        let agencyID = agencyTable.column("agency_id")
        let agencyZone = agencyTable.column("agency_timezone")
        var operatorAgencies = Set<String>()
        var allAgencies = 0
        try agencyTable.forEachRow { row in
            allAgencies += 1
            let id = row.value(agencyID)
            let name = row.value(agencyName)
            guard !name.isEmpty, !operatorAgencies.contains(id),
                  TimetableNaming.operatorName(name, sourceID: sourceID) == operatorScope else { return }
            let zone = row.value(agencyZone)
            let timezone = !zone.isEmpty && TimeZone(identifier: zone) != nil ? zone : "Europe/Rome"
            operatorAgencies.insert(id)
            feed.agencies.append(Agency(externalID: id, name: name, timezone: timezone))
        }
        guard !feed.agencies.isEmpty else { throw RemoteGTFSIndexFailure.noTrains }
        // A route without agency_id belongs to the feed's only agency.
        let onlyAgency: String? = allAgencies == 1 ? feed.agencies[0].externalID : nil

        // Routes of the operator that are a train or a bus leg (Android's journeyLegMode; an empty type is a train).
        guard let routeTable = try table("routes.txt", rows: limits.maximumRoutes),
              let routeID = routeTable.column("route_id") else { throw RemoteGTFSIndexFailure.invalidFeed }
        let routeAgency = routeTable.column("agency_id")
        let routeShort = routeTable.column("route_short_name")
        let routeLong = routeTable.column("route_long_name")
        let routeType = routeTable.column("route_type")
        var keptRoutes = Set<String>()
        try routeTable.forEachRow { row in
            let id = row.value(routeID)
            guard !id.isEmpty, !keptRoutes.contains(id) else { return }
            var agency = row.value(routeAgency)
            if agency.isEmpty, let onlyAgency { agency = onlyAgency }
            guard operatorAgencies.contains(agency) else { return }
            let typeText = row.value(routeType)
            guard let type = typeText.isEmpty ? 2 : Int(typeText),
                  let mode = TimetableLegMode.of(routeType: Int64(type), sourceID: sourceID), !mode.isUrban else { return }
            keptRoutes.insert(id)
            feed.routes.append(Route(
                externalID: id, agency: agency, shortName: row.value(routeShort), longName: row.value(routeLong), type: type
            ))
        }
        guard !feed.routes.isEmpty else { throw RemoteGTFSIndexFailure.noTrains }

        guard let tripTable = try table("trips.txt", rows: limits.maximumTrips),
              let tripRoute = tripTable.column("route_id"), let tripService = tripTable.column("service_id"),
              let tripID = tripTable.column("trip_id") else { throw RemoteGTFSIndexFailure.invalidFeed }
        let tripHeadsign = tripTable.column("trip_headsign")
        let tripShort = tripTable.column("trip_short_name")
        let tripDirection = tripTable.column("direction_id")
        let tripBikes = tripTable.column("bikes_allowed")
        var trips: [String: Trip] = [:]
        var tripOrder: [String] = []
        try tripTable.forEachRow { row in
            let id = row.value(tripID)
            let route = row.value(tripRoute)
            guard !id.isEmpty, trips[id] == nil, keptRoutes.contains(route), !row.value(tripService).isEmpty else { return }
            let bikes = Int(row.value(tripBikes)).flatMap { $0 == 1 || $0 == 2 ? $0 : nil }
            trips[id] = Trip(
                externalID: id, route: route, service: row.value(tripService), headsign: row.value(tripHeadsign),
                shortName: row.value(tripShort), direction: Int(row.value(tripDirection)), bikes: bikes
            )
            tripOrder.append(id)
        }

        // Services of the kept trips: calendar.txt, then calendar_dates.txt (1 adds a day, 2 removes it).
        let wantedServices = Set(trips.values.map(\.service))
        var weekly: [String: (days: [Bool], start: Date, end: Date)] = [:]
        if let calendarTable = try table("calendar.txt", rows: limits.maximumCalendars) {
            let id = calendarTable.column("service_id")
            let dayColumns = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"].map {
                calendarTable.column($0)
            }
            let start = calendarTable.column("start_date")
            let end = calendarTable.column("end_date")
            try calendarTable.forEachRow { row in
                let service = row.value(id)
                guard wantedServices.contains(service), weekly[service] == nil,
                      let first = Self.date(row.value(start)), let last = Self.date(row.value(end)), first <= last else { return }
                weekly[service] = (days: dayColumns.map { row.value($0) == "1" }, start: first, end: last)
            }
        }
        var exceptions: [String: [(day: Date, added: Bool)]] = [:]
        if let datesTable = try table("calendar_dates.txt", rows: limits.maximumCalendarDateRows) {
            let id = datesTable.column("service_id")
            let date = datesTable.column("date")
            let kind = datesTable.column("exception_type")
            try datesTable.forEachRow { row in
                let service = row.value(id)
                guard wantedServices.contains(service), let day = Self.date(row.value(date)) else { return }
                switch row.value(kind) {
                case "1": exceptions[service, default: []].append((day: day, added: true))
                case "2": exceptions[service, default: []].append((day: day, added: false))
                default: break
                }
            }
        }
        for service in wantedServices.sorted() {
            var active = Set<Date>()
            if let week = weekly[service] {
                var day = week.start
                var count = 0
                while day <= week.end, count < maximumServiceDays {
                    count += 1
                    if week.days[Self.weekdayIndex(day)] { active.insert(day) }
                    guard let next = Self.calendar.date(byAdding: .day, value: 1, to: day) else { break }
                    day = next
                }
            }
            for exception in exceptions[service] ?? [] {
                if exception.added { active.insert(exception.day) } else { active.remove(exception.day) }
            }
            guard let first = active.min(), let last = active.max(),
                  let span = Self.calendar.dateComponents([.day], from: first, to: last).day,
                  span + 1 <= maximumServiceDays else { continue }
            let dayCount = span + 1
            var bits = [UInt8](repeating: 0, count: (dayCount + 7) / 8)
            for day in active {
                guard let offset = Self.calendar.dateComponents([.day], from: first, to: day).day else { continue }
                bits[offset / 8] |= UInt8(1 << (offset % 8))
            }
            feed.services.append(Service(externalID: service, startDate: Self.encoded(first), dayCount: dayCount, activeDays: bits))
        }
        let runningServices = Set(feed.services.map(\.externalID))

        // The calls of the kept trips.
        guard let timeTable = try table("stop_times.txt", rows: limits.maximumStopTimeRows),
              let timeTrip = timeTable.column("trip_id"), let timeStop = timeTable.column("stop_id"),
              let timeSequence = timeTable.column("stop_sequence") else { throw RemoteGTFSIndexFailure.invalidFeed }
        let timeArrival = timeTable.column("arrival_time")
        let timeDeparture = timeTable.column("departure_time")
        let timePickup = timeTable.column("pickup_type")
        let timeDropOff = timeTable.column("drop_off_type")
        var calls: [String: [Call]] = [:]
        try timeTable.forEachRow { row in
            let trip = row.value(timeTrip)
            guard let kept = trips[trip], runningServices.contains(kept.service),
                  let sequence = Int(row.value(timeSequence)), sequence >= 0, !row.value(timeStop).isEmpty else { return }
            // Android shows a stop's readable time when the other is not: the index stores both or neither.
            let arrival = RemoteGTFSIndex.seconds(row.value(timeArrival))
            let departure = RemoteGTFSIndex.seconds(row.value(timeDeparture))
            calls[trip, default: []].append(Call(
                sequence: sequence, stop: row.value(timeStop), arrival: arrival ?? departure, departure: departure ?? arrival,
                pickup: Self.boardingType(row.value(timePickup)), dropOff: Self.boardingType(row.value(timeDropOff))
            ))
        }

        // Stops: every stop a kept call names.
        guard let stopTable = try table("stops.txt", rows: limits.maximumStops),
              let stopID = stopTable.column("stop_id"), let stopName = stopTable.column("stop_name") else {
            throw RemoteGTFSIndexFailure.invalidFeed
        }
        let stopLatitude = stopTable.column("stop_lat")
        let stopLongitude = stopTable.column("stop_lon")
        var wantedStops = Set<String>()
        for tripCalls in calls.values { for call in tripCalls { wantedStops.insert(call.stop) } }
        var stops: [String: Stop] = [:]
        try stopTable.forEachRow { row in
            let id = row.value(stopID)
            let name = row.value(stopName)
            guard wantedStops.contains(id), stops[id] == nil, !name.isEmpty else { return }
            let latitude = Double(row.value(stopLatitude)).flatMap { (-90...90).contains($0) ? $0 : nil }
            let longitude = Double(row.value(stopLongitude)).flatMap { (-180...180).contains($0) ? $0 : nil }
            let placed = latitude != nil && longitude != nil
            stops[id] = Stop(externalID: id, name: name, latitude: placed ? latitude : nil, longitude: placed ? longitude : nil)
        }

        // A trip is kept whole: at least two known stops, one call per sequence, never back in time.
        for id in tripOrder {
            guard let trip = trips[id], runningServices.contains(trip.service), var tripCalls = calls[id],
                  tripCalls.count >= 2 else { continue }
            tripCalls.sort { $0.sequence < $1.sequence }
            guard Set(tripCalls.map(\.sequence)).count == tripCalls.count,
                  tripCalls.allSatisfy({ stops[$0.stop] != nil }) else { continue }
            var latest = -1
            var ordered = true
            for call in tripCalls {
                for time in [call.arrival, call.departure].compactMap({ $0 }) {
                    if time < latest { ordered = false }
                    latest = max(latest, time)
                }
            }
            guard ordered else { continue }
            feed.trips.append(trip)
            feed.calls[id] = tripCalls
        }
        guard !feed.trips.isEmpty else { throw RemoteGTFSIndexFailure.noTrains }
        var usedStops = Set<String>()
        for tripCalls in feed.calls.values { for call in tripCalls { usedStops.insert(call.stop) } }
        feed.stops = stops.values.filter { usedStops.contains($0.externalID) }.sorted { $0.externalID < $1.externalID }
        let usedRoutes = Set(feed.trips.map(\.route))
        feed.routes = feed.routes.filter { usedRoutes.contains($0.externalID) }
        let usedAgencies = Set(feed.routes.map(\.agency))
        feed.agencies = feed.agencies.filter { usedAgencies.contains($0.externalID) }
        let usedServices = Set(feed.trips.map(\.service))
        feed.services = feed.services.filter { usedServices.contains($0.externalID) }
        return feed
    }

    /// GTFS pickup_type and drop_off_type: 0 to 3, empty or unknown as 0 (regular).
    private static func boardingType(_ value: String) -> Int {
        guard let type = Int(value), (0...3).contains(type) else { return 0 }
        return type
    }

    static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .current
        return calendar
    }()

    /// yyyymmdd as a UTC midnight, nil for a date no calendar has.
    static func date(_ value: String) -> Date? {
        guard value.count == 8, value.allSatisfy({ $0.isASCII && $0.isNumber }), let number = Int(value) else { return nil }
        let year = number / 10_000, month = number / 100 % 100, day = number % 100
        guard (1900...2999).contains(year), (1...12).contains(month), (1...31).contains(day),
              let date = calendar.date(from: DateComponents(year: year, month: month, day: day)),
              calendar.component(.day, from: date) == day, calendar.component(.month, from: date) == month else { return nil }
        return date
    }

    static func encoded(_ date: Date) -> Int {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return (parts.year ?? 0) * 10_000 + (parts.month ?? 0) * 100 + (parts.day ?? 0)
    }

    /// 0 for Monday … 6 for Sunday.
    private static func weekdayIndex(_ date: Date) -> Int {
        (calendar.component(.weekday, from: date) + 5) % 7
    }
}

// MARK: - Writing the index

/// The index file: the schema of tools/build_ios_gtfs_schedule.py (SCHEMA_SQL), one pack, written in one transaction
/// to a staged file that replaces `destination` only once complete.
private enum RemoteIndexWriter {
    static let schema = """
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

    static func write(
        _ feed: RemoteFeedData, feed source: SharedData.RemoteGtfs.RemoteFeed, match: RemoteGTFSStationMatch,
        fingerprint: String, generatedOn: Date, into destination: URL
    ) throws {
        let staged = destination.deletingLastPathComponent()
            .appendingPathComponent(".staged-\(UUID().uuidString).sqlite", isDirectory: false)
        defer { try? FileManager.default.removeItem(at: staged) }
        do {
            let writer = try RemoteSQLiteWriter(url: staged)
            try writer.execute(schema)
            try writer.execute("PRAGMA application_id = \(SQLiteGTFSJourneyRepository.applicationID)")
            try writer.execute("PRAGMA user_version = \(SQLiteGTFSJourneyRepository.userVersion)")
            try writer.execute("BEGIN")
            var lastService = 0
            for service in feed.services {
                guard let start = RemoteFeedData.date(String(service.startDate)),
                      let last = RemoteFeedData.calendar.date(byAdding: .day, value: service.dayCount - 1, to: start) else { continue }
                lastService = max(lastService, RemoteFeedData.encoded(last))
            }
            try writer.insert("INSERT INTO pack VALUES (1, ?, ?, ?)", [
                .text(source.pack), .text(source.attribution), .integer(Int64(lastService)),
            ])
            var agencyIDs: [String: Int64] = [:]
            for (index, agency) in feed.agencies.enumerated() {
                let id = Int64(index + 1)
                agencyIDs[agency.externalID] = id
                try writer.insert("INSERT INTO agency VALUES (?, 1, ?, ?, ?)", [
                    .integer(id), .text(agency.externalID), .text(agency.name), .text(agency.timezone),
                ])
            }
            var routeIDs: [String: Int64] = [:]
            for (index, route) in feed.routes.enumerated() {
                let id = Int64(index + 1)
                guard let agency = agencyIDs[route.agency] else { continue }
                routeIDs[route.externalID] = id
                try writer.insert("INSERT INTO route VALUES (?, 1, ?, ?, ?, ?, ?)", [
                    .integer(id), .integer(agency), .text(route.externalID), .optionalText(route.shortName),
                    .optionalText(route.longName), .integer(Int64(route.type)),
                ])
            }
            var serviceIDs: [String: Int64] = [:]
            for (index, service) in feed.services.enumerated() {
                let id = Int64(index + 1)
                serviceIDs[service.externalID] = id
                try writer.insert("INSERT INTO service VALUES (?, 1, ?, ?, ?, ?)", [
                    .integer(id), .text(service.externalID), .integer(Int64(service.startDate)),
                    .integer(Int64(service.dayCount)), .blob(Data(service.activeDays)),
                ])
            }
            var stopIDs: [String: Int64] = [:]
            for (index, stop) in feed.stops.enumerated() {
                let id = Int64(index + 1)
                stopIDs[stop.externalID] = id
                let station = match.station(stopName: stop.name, latitude: stop.latitude, longitude: stop.longitude)
                try writer.insert("INSERT INTO stop VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?)", [
                    .integer(id), .text(stop.externalID), .text(stop.name), .optionalReal(stop.latitude),
                    .optionalReal(stop.longitude), .optionalText(station?.id), .optionalText(station?.railwayCode),
                    .optionalText(station?.trenitaliaLocationId),
                ])
            }
            var stopTimes = 0
            for (index, trip) in feed.trips.enumerated() {
                let id = Int64(index + 1)
                guard let route = routeIDs[trip.route], let service = serviceIDs[trip.service] else { continue }
                try writer.insert("INSERT INTO trip VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?)", [
                    .integer(id), .integer(route), .integer(service), .text(trip.externalID), .optionalText(trip.headsign),
                    .optionalText(trip.shortName), .optionalInteger(trip.direction), .optionalInteger(trip.bikes),
                ])
                for call in feed.calls[trip.externalID] ?? [] {
                    guard let stop = stopIDs[call.stop] else { continue }
                    try writer.insert("INSERT INTO stop_time VALUES (?, ?, ?, ?, ?, ?, ?)", [
                        .integer(id), .integer(Int64(call.sequence)), .integer(stop),
                        .optionalInteger(call.arrival), .optionalInteger(call.departure),
                        .integer(Int64(call.pickup)), .integer(Int64(call.dropOff)),
                    ])
                    stopTimes += 1
                }
            }
            let counts: [String: Int] = [
                "agency": feed.agencies.count, "route": feed.routes.count, "service": feed.services.count,
                "stop": feed.stops.count, "trip": feed.trips.count, "stop_time": stopTimes,
            ]
            let countsData = try JSONSerialization.data(withJSONObject: counts, options: [.sortedKeys])
            let countsJSON = String(decoding: countsData, as: UTF8.self)
            let metadata: [(String, String)] = [
                ("schema_version", String(SQLiteGTFSJourneyRepository.userVersion)),
                ("input_fingerprint_sha256", fingerprint),
                ("source_generated_at", LiveRailTime.isoDay(generatedOn)),
                ("row_counts_json", countsJSON),
                ("remote_source_url", source.url),
            ]
            for (key, value) in metadata {
                try writer.insert("INSERT INTO metadata VALUES (?, ?)", [.text(key), .text(value)])
            }
            try writer.execute("COMMIT")
            writer.close()
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.moveItem(at: staged, to: destination)
        } catch let failure as RemoteGTFSIndexFailure {
            throw failure
        } catch {
            throw RemoteGTFSIndexFailure.storage
        }
    }
}

/// A read-write SQLite connection, for the index writer only.
private final class RemoteSQLiteWriter {
    enum Value {
        case integer(Int64)
        case real(Double)
        case text(String)
        case blob(Data)
        case null

        static func optionalText(_ value: String?) -> Value {
            guard let value, !value.isEmpty else { return .null }
            return .text(value)
        }

        static func optionalReal(_ value: Double?) -> Value {
            guard let value else { return .null }
            return .real(value)
        }

        static func optionalInteger(_ value: Int?) -> Value {
            guard let value else { return .null }
            return .integer(Int64(value))
        }
    }

    private var handle: OpaquePointer?
    private var statements: [String: OpaquePointer] = [:]

    init(url: URL) throws {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        var opened: OpaquePointer?
        guard sqlite3_open_v2(url.path, &opened, flags, nil) == SQLITE_OK, let opened else {
            if let opened { sqlite3_close_v2(opened) }
            throw RemoteGTFSIndexFailure.storage
        }
        handle = opened
    }

    deinit { close() }

    func close() {
        for statement in statements.values { sqlite3_finalize(statement) }
        statements = [:]
        if let handle { sqlite3_close_v2(handle) }
        handle = nil
    }

    func execute(_ sql: String) throws {
        guard let handle, sqlite3_exec(handle, sql, nil, nil, nil) == SQLITE_OK else { throw RemoteGTFSIndexFailure.storage }
    }

    func insert(_ sql: String, _ values: [Value]) throws {
        guard let handle else { throw RemoteGTFSIndexFailure.storage }
        let statement: OpaquePointer
        if let prepared = statements[sql] {
            statement = prepared
        } else {
            var created: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &created, nil) == SQLITE_OK, let created else {
                throw RemoteGTFSIndexFailure.storage
            }
            statements[sql] = created
            statement = created
        }
        sqlite3_reset(statement)
        sqlite3_clear_bindings(statement)
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let status: Int32
            switch value {
            case let .integer(number):
                status = sqlite3_bind_int64(statement, index, number)
            case let .real(number):
                status = sqlite3_bind_double(statement, index, number)
            case let .text(text):
                status = sqlite3_bind_text(statement, index, text, -1, binario_sqlite_transient())
            case let .blob(data):
                status = data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) -> Int32 in
                    sqlite3_bind_blob(statement, index, buffer.baseAddress, Int32(buffer.count), binario_sqlite_transient())
                }
            case .null:
                status = sqlite3_bind_null(statement, index)
            }
            guard status == SQLITE_OK else { throw RemoteGTFSIndexFailure.storage }
        }
        guard sqlite3_step(statement) == SQLITE_DONE else { throw RemoteGTFSIndexFailure.storage }
    }
}
