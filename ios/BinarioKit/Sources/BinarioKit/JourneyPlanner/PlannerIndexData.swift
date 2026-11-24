import Foundation

// What the planner of journeys with changes reads from one index file: its packs, each pack's stops as transfer points
// (Android's `PackStops`), each pack's trips and stop times read once (Android's compact pack index), and the
// connections of one service day (Android's `GtfsDayTimetable`). Read-only, built once and cached, so concurrent
// searches share them.

/// A GTFS wheelchair value: 1 accessible, 2 not; anything else unknown (never accessible).
enum PlannerWheelchair {
    static let unknown: UInt8 = 0
    static let yes: UInt8 = 1
    static let no: UInt8 = 2

    static func of(_ value: Int64?) -> UInt8 {
        switch value {
        case 1: return yes
        case 2: return no
        default: return unknown
        }
    }
}

/// One pack of one index file.
struct PlannerPack: Sendable {
    /// The file's path and size: the cache key of what is read from it.
    let fileKey: String
    let fileURL: URL
    let rowID: Int64
    let sourceID: String
    /// yyyymmdd.
    let lastServiceDate: Int
    let isUrban: Bool
    /// The file has `trip_detail` and `stop_detail`.
    let hasDetails: Bool

    var cacheKey: String { "\(fileKey)|\(sourceID)" }
}

/// The stops of one pack as transfer points: the catalogue station a stop is bound to, else the place the index gives
/// it (`stop_detail.place`: Android's parent group or same-name place), else the place of its name.
final class PlannerPackStops: @unchecked Sendable {
    struct Row {
        let rowID: Int64
        let externalID: String
        let name: String
        let latitude: Double?
        let longitude: Double?
        let catalogueID: String?
        let railwayCode: String?
        let place: String?
        let wheelchair: UInt8
    }

    let sourceID: String
    let externalIDs: [String]
    let names: [String]
    let latitudes: [Double]
    let longitudes: [Double]
    let railwayCodes: [String?]
    let wheelchair: [UInt8]
    let nodeKeys: [String]
    let indexByRowID: [Int64: Int]
    let indexByExternalID: [String: Int]
    let stopsByNode: [String: [Int]]
    let catalogueStations: Set<String>

    init(sourceID: String, rows: [Row]) {
        self.sourceID = sourceID
        externalIDs = rows.map(\.externalID)
        names = rows.map(\.name)
        latitudes = rows.map { $0.latitude ?? .nan }
        longitudes = rows.map { $0.longitude ?? .nan }
        railwayCodes = rows.map(\.railwayCode)
        wheelchair = rows.map(\.wheelchair)
        let keys = rows.map { row in
            row.catalogueID ?? row.place ?? "\(sourceID):name:\(TimetableNaming.normalize(row.name))"
        }
        nodeKeys = keys
        var byRow: [Int64: Int] = [:]
        var byExternal: [String: Int] = [:]
        var byNode: [String: [Int]] = [:]
        for (index, row) in rows.enumerated() {
            byRow[row.rowID] = index
            byExternal[row.externalID] = index
            byNode[keys[index], default: []].append(index)
        }
        indexByRowID = byRow
        indexByExternalID = byExternal
        stopsByNode = byNode
        catalogueStations = Set(rows.compactMap(\.catalogueID))
    }

    var count: Int { externalIDs.count }

    /// True only when the stop is published as wheelchair accessible.
    func stopAccessible(_ index: Int) -> Bool { wheelchair[index] == PlannerWheelchair.yes }

    /// Some stop of the transfer point is published as accessible: a walk may start or end there.
    func nodeHasAccessibleStop(_ node: String) -> Bool {
        stopsByNode[node]?.contains { wheelchair[$0] == PlannerWheelchair.yes } == true
    }

    /// Some stop of `nodes` has a published wheelchair value (accessible or not): the area has data.
    func wheelchairKnown<Nodes: Sequence>(_ nodes: Nodes) -> Bool where Nodes.Element == String {
        nodes.contains { node in stopsByNode[node]?.contains { wheelchair[$0] != PlannerWheelchair.unknown } == true }
    }

    /// The catalogue station's coordinates for a catalogue node, else those of its first stop with them.
    func point(_ node: String, stations: [String: Station]) -> (latitude: Double, longitude: Double)? {
        if let station = stations[node], let latitude = station.latitude, let longitude = station.longitude {
            return (latitude: latitude, longitude: longitude)
        }
        for index in stopsByNode[node] ?? [] where !latitudes[index].isNaN && !longitudes[index].isNaN {
            return (latitude: latitudes[index], longitude: longitudes[index])
        }
        return nil
    }

    /// Coordinates of one stop, else of its transfer point.
    func stopPoint(_ index: Int, stations: [String: Station]) -> (latitude: Double, longitude: Double)? {
        if !latitudes[index].isNaN, !longitudes[index].isNaN { return (latitude: latitudes[index], longitude: longitudes[index]) }
        return point(nodeKeys[index], stations: stations)
    }
}

/// One trip of a pack as the planner names it (Android's `GtfsDayTrip` without its connections).
struct PlannerTrip: Sendable {
    let rowID: Int64
    let externalID: String
    /// trip_short_name, else a numeric headsign, else the route's short name, else the trip id.
    let publicNumber: String
    /// The trip publishes its own number (trip_short_name or a numeric headsign).
    let hasTrainNumber: Bool
    /// The route's short name as shown (Trenitalia's «0083_FR» is «FR»).
    let lineLabel: String?
    /// The route's long name, shown for a city line without a short name.
    let longName: String?
    let operatorName: String
    let category: JourneyTrainCategory
    let mode: TimetableLegMode
    let serviceRowID: Int64
    let blockID: String?
    let wheelchair: UInt8
    let bikesAllowed: Bool?
    /// The first of its stop times in the pack's arrays and their number.
    var firstStopTime = 0
    var stopTimeCount = 0
}

/// The trips and stop times of one pack, read once.
final class PlannerPackTimetable: @unchecked Sendable {
    let sourceID: String
    let trips: [PlannerTrip]
    /// Per stop time: the stop (index of `PlannerPackStops`), arrival and departure minutes (-1 untimed), no pickup or
    /// no drop off (GTFS 1).
    let stopIndex: [Int32]
    let arrival: [Int32]
    let departure: [Int32]
    let flags: [UInt8]
    /// service row id → start (yyyymmdd), day count, active days.
    let services: [Int64: PlannerService]

    init(sourceID: String, trips: [PlannerTrip], stopIndex: [Int32], arrival: [Int32], departure: [Int32], flags: [UInt8],
         services: [Int64: PlannerService]) {
        self.sourceID = sourceID
        self.trips = trips
        self.stopIndex = stopIndex
        self.arrival = arrival
        self.departure = departure
        self.flags = flags
        self.services = services
    }

    var stopTimeCount: Int { stopIndex.count }
}

struct PlannerService: Sendable {
    let startDate: Int64
    let dayCount: Int64
    let activeDays: Data
}

/// The connections of one pack on one service day, in trip order (Android's `GtfsDayTimetable`). Minutes count from
/// the service day's GTFS reference (noon minus twelve hours), so a run after midnight keeps 24:00+ values.
final class PlannerDay: @unchecked Sendable {
    let sourceID: String
    /// yyyymmdd.
    let serviceDate: Int
    let trips: [PlannerTrip]
    /// Per day trip, its first connection and their number.
    let tripFirstConnection: [Int]
    let tripConnectionCount: [Int]
    let connectionTrip: [Int]
    /// Stops are indices of the pack's `PlannerPackStops`.
    let departureStop: [Int]
    let arrivalStop: [Int]
    let departureMinute: [Int]
    let arrivalMinute: [Int]
    let boarding: [UInt8]
    let lastDepartureMinute: Int
    /// The trips of every GTFS block with two trips or more, each in order of first departure.
    let blockSequences: [[Int]]
    /// The stops the connections use, in order of first use (Android's day-local stop table).
    let dayStops: [Int]

    init(sourceID: String, serviceDate: Int, trips: [PlannerTrip], tripFirstConnection: [Int], tripConnectionCount: [Int],
         connectionTrip: [Int], departureStop: [Int], arrivalStop: [Int], departureMinute: [Int], arrivalMinute: [Int],
         boarding: [UInt8]) {
        self.sourceID = sourceID
        self.serviceDate = serviceDate
        self.trips = trips
        self.tripFirstConnection = tripFirstConnection
        self.tripConnectionCount = tripConnectionCount
        self.connectionTrip = connectionTrip
        self.departureStop = departureStop
        self.arrivalStop = arrivalStop
        self.departureMinute = departureMinute
        self.arrivalMinute = arrivalMinute
        self.boarding = boarding
        lastDepartureMinute = departureMinute.max() ?? -1
        var seen = Set<Int>()
        var used: [Int] = []
        for connection in connectionTrip.indices {
            if seen.insert(departureStop[connection]).inserted { used.append(departureStop[connection]) }
            if seen.insert(arrivalStop[connection]).inserted { used.append(arrivalStop[connection]) }
        }
        dayStops = used
        var byBlock: [String: [Int]] = [:]
        var order: [String] = []
        for (index, trip) in trips.enumerated() {
            guard let block = trip.blockID else { continue }
            if byBlock[block] == nil { order.append(block) }
            byBlock[block, default: []].append(index)
        }
        blockSequences = order.compactMap { block -> [Int]? in
            guard let members = byBlock[block], members.count > 1 else { return nil }
            return members.enumerated().sorted { first, second in
                let a = departureMinute[tripFirstConnection[first.element]]
                let b = departureMinute[tripFirstConnection[second.element]]
                return a != b ? a < b : first.offset < second.offset
            }.map(\.element)
        }
    }

    var connectionCount: Int { connectionTrip.count }
    func pickupAllowed(_ connection: Int) -> Bool { boarding[connection] & ConnectionScanTimetable.noPickup == 0 }
    func dropOffAllowed(_ connection: Int) -> Bool { boarding[connection] & ConnectionScanTimetable.noDropOff == 0 }

    /// Rough retained size, for the cache bound.
    var approximateBytes: Int { connectionCount * 49 + trips.count * 200 }

    /// Android's `dayTimetable`: every trip of the pack running on `serviceDay`, its timed stops in order; a stop whose
    /// time goes back is skipped like an untimed one.
    static func build(pack: PlannerPackTimetable, serviceDay: Date, serviceDate: Int, calendar: Calendar) -> PlannerDay {
        var trips: [PlannerTrip] = []
        var firsts: [Int] = []
        var counts: [Int] = []
        var connectionTrip: [Int] = []
        var departureStop: [Int] = []
        var arrivalStop: [Int] = []
        var departureMinute: [Int] = []
        var arrivalMinute: [Int] = []
        var boarding: [UInt8] = []
        var running: [Int64: Bool] = [:]
        for trip in pack.trips {
            let runs: Bool
            if let known = running[trip.serviceRowID] {
                runs = known
            } else if let service = pack.services[trip.serviceRowID] {
                runs = SQLiteGTFSJourneyRepository.serviceRuns(
                    serviceDay: serviceDay, startDate: service.startDate, dayCount: service.dayCount,
                    activeDays: service.activeDays, calendar: calendar
                )
                running[trip.serviceRowID] = runs
            } else {
                runs = false
            }
            guard runs else { continue }
            let first = connectionTrip.count
            var previous = -1
            var lastArrival = Int.min
            for entry in trip.firstStopTime..<(trip.firstStopTime + trip.stopTimeCount) {
                guard preferredDeparture(pack, entry) >= 0 else { continue }
                if previous >= 0 {
                    let departure = preferredDeparture(pack, previous)
                    let arrival = preferredArrival(pack, entry)
                    if departure < lastArrival || arrival < departure { continue }
                    connectionTrip.append(trips.count)
                    departureStop.append(Int(pack.stopIndex[previous]))
                    arrivalStop.append(Int(pack.stopIndex[entry]))
                    departureMinute.append(departure)
                    arrivalMinute.append(arrival)
                    boarding.append(ConnectionScanTimetable.boardingFlags(
                        pickupAllowed: pack.flags[previous] & ConnectionScanTimetable.noPickup == 0,
                        dropOffAllowed: pack.flags[entry] & ConnectionScanTimetable.noDropOff == 0
                    ))
                    lastArrival = arrival
                }
                previous = entry
            }
            let count = connectionTrip.count - first
            // A trip without any connection leaves no trace.
            guard count > 0 else { continue }
            trips.append(trip)
            firsts.append(first)
            counts.append(count)
        }
        return PlannerDay(
            sourceID: pack.sourceID, serviceDate: serviceDate, trips: trips, tripFirstConnection: firsts, tripConnectionCount: counts,
            connectionTrip: connectionTrip, departureStop: departureStop, arrivalStop: arrivalStop,
            departureMinute: departureMinute, arrivalMinute: arrivalMinute, boarding: boarding
        )
    }

    private static func preferredDeparture(_ pack: PlannerPackTimetable, _ entry: Int) -> Int {
        let departure = Int(pack.departure[entry])
        return departure >= 0 ? departure : Int(pack.arrival[entry])
    }

    private static func preferredArrival(_ pack: PlannerPackTimetable, _ entry: Int) -> Int {
        let arrival = Int(pack.arrival[entry])
        return arrival >= 0 ? arrival : Int(pack.departure[entry])
    }
}

/// The planner's caches, shared by every search of the process: pack stops, pack timetables (bounded by their stop
/// times) and service days (bounded by count and size, as Android's day cache).
final class PlannerCache: @unchecked Sendable {
    static let shared = PlannerCache()

    /// Stop times of the pack timetables kept (the base index has about a million).
    static let maximumStopTimes = 1_500_000
    static let maximumDays = 16
    static let maximumDayBytes = 24 * 1_024 * 1_024

    private let lock = NSLock()
    private var stops: [String: PlannerPackStops] = [:]
    private var timetables: [String: PlannerPackTimetable] = [:]
    private var timetableOrder: [String] = []
    private var days: [String: PlannerDay] = [:]
    private var dayOrder: [String] = []

    func packStops(_ key: String, load: () throws -> PlannerPackStops) rethrows -> PlannerPackStops {
        if let found = locked({ stops[key] }) { return found }
        let loaded = try load()
        return locked {
            if let found = stops[key] { return found }
            if stops.count >= 128 { stops.removeAll() }
            stops[key] = loaded
            return loaded
        }
    }

    func packTimetable(_ key: String, load: () throws -> PlannerPackTimetable) rethrows -> PlannerPackTimetable {
        if let found = locked({ () -> PlannerPackTimetable? in
            guard let found = timetables[key] else { return nil }
            timetableOrder.removeAll { $0 == key }
            timetableOrder.append(key)
            return found
        }) { return found }
        let loaded = try load()
        return locked {
            if let found = timetables[key] { return found }
            timetables[key] = loaded
            timetableOrder.append(key)
            var total = timetables.values.reduce(0) { $0 + $1.stopTimeCount }
            var index = 0
            while total > Self.maximumStopTimes, index < timetableOrder.count {
                let eldest = timetableOrder[index]
                if eldest == key {
                    index += 1
                    continue
                }
                timetableOrder.remove(at: index)
                total -= timetables[eldest]?.stopTimeCount ?? 0
                timetables[eldest] = nil
            }
            return loaded
        }
    }

    func day(_ key: String, build: () throws -> PlannerDay) rethrows -> PlannerDay {
        if let found = locked({ () -> PlannerDay? in
            guard let found = days[key] else { return nil }
            dayOrder.removeAll { $0 == key }
            dayOrder.append(key)
            return found
        }) { return found }
        let built = try build()
        return locked {
            if let found = days[key] { return found }
            days[key] = built
            dayOrder.append(key)
            var bytes = days.values.reduce(0) { $0 + $1.approximateBytes }
            var index = 0
            while days.count > Self.maximumDays || bytes > Self.maximumDayBytes, index < dayOrder.count {
                let eldest = dayOrder[index]
                if eldest == key {
                    index += 1
                    continue
                }
                dayOrder.remove(at: index)
                bytes -= days[eldest]?.approximateBytes ?? 0
                days[eldest] = nil
            }
            return built
        }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

extension SQLiteGTFSJourneyRepository {
    /// Bounds of one pack read by the planner.
    static let maximumPlannerStops = 32_768
    static let maximumPlannerTrips = 250_000

    /// The packs of one file, in ingestion order. `excluded`: the packs another file answers for.
    static func plannerPacks(in database: SQLiteDatabase, url: URL, excluded: Set<String>) throws -> [PlannerPack] {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes?[.size] as? NSNumber)?.intValue ?? 0
        let fileKey = "\(url.standardizedFileURL.path)|\(size)"
        let details = try database.hasDetailTables()
        let statement = try database.statement("SELECT id, source_id, last_service_date FROM pack ORDER BY id LIMIT 65")
        var packs: [PlannerPack] = []
        while try statement.step() {
            guard let sourceID = statement.text(at: 1), !sourceID.isEmpty else { throw SQLiteGTFSFailure.invalidData }
            if excluded.contains(sourceID) { continue }
            packs.append(PlannerPack(
                fileKey: fileKey, fileURL: url, rowID: statement.int(at: 0), sourceID: sourceID,
                lastServiceDate: Int(statement.int(at: 2)), isUrban: UrbanStops.packs.contains(sourceID), hasDetails: details
            ))
        }
        return packs
    }

    /// The stops of `pack` as transfer points.
    static func plannerStops(_ pack: PlannerPack, database: SQLiteDatabase) throws -> PlannerPackStops {
        try PlannerCache.shared.packStops(pack.cacheKey) {
            let detail = pack.hasDetails ? "d.place, d.wheelchair_boarding" : "NULL, NULL"
            let join = pack.hasDetails ? "LEFT JOIN stop_detail AS d ON d.stop_id = s.id" : ""
            let statement = try database.statement(
                """
                SELECT s.id, s.external_id, s.name, s.latitude, s.longitude, s.catalog_station_id, s.railway_code, \(detail)
                FROM stop AS s \(join)
                WHERE s.pack_id = ?
                ORDER BY s.id
                LIMIT ?
                """
            )
            try statement.bind(pack.rowID, at: 1)
            try statement.bind(Int64(maximumPlannerStops + 1), at: 2)
            var rows: [PlannerPackStops.Row] = []
            while try statement.step() {
                guard rows.count < maximumPlannerStops else { throw SQLiteGTFSFailure.limitExceeded }
                guard let externalID = statement.text(at: 1), !externalID.isEmpty, let name = statement.text(at: 2) else {
                    throw SQLiteGTFSFailure.invalidData
                }
                rows.append(PlannerPackStops.Row(
                    rowID: statement.int(at: 0), externalID: externalID, name: name,
                    latitude: statement.double(at: 3), longitude: statement.double(at: 4),
                    catalogueID: nonEmpty(statement.text(at: 5)),
                    railwayCode: nonEmpty(statement.text(at: 6)),
                    place: nonEmpty(statement.text(at: 7)),
                    wheelchair: PlannerWheelchair.of(statement.optionalInt(at: 8))
                ))
            }
            return PlannerPackStops(sourceID: pack.sourceID, rows: rows)
        }
    }

    /// The trips and stop times of `pack`, read once.
    static func plannerTimetable(_ pack: PlannerPack, stops: PlannerPackStops, database: SQLiteDatabase) throws -> PlannerPackTimetable {
        try PlannerCache.shared.packTimetable(pack.cacheKey) {
            var services: [Int64: PlannerService] = [:]
            let serviceStatement = try database.statement(
                "SELECT id, start_date, day_count, active_days FROM service WHERE pack_id = ? ORDER BY id"
            )
            try serviceStatement.bind(pack.rowID, at: 1)
            while try serviceStatement.step() {
                guard let days = serviceStatement.blob(at: 3) else { throw SQLiteGTFSFailure.invalidData }
                services[serviceStatement.int(at: 0)] = PlannerService(
                    startDate: serviceStatement.int(at: 1), dayCount: serviceStatement.int(at: 2), activeDays: days
                )
            }
            let detail = pack.hasDetails ? "d.block_id, d.wheelchair_accessible" : "NULL, NULL"
            let join = pack.hasDetails ? "LEFT JOIN trip_detail AS d ON d.trip_id = t.id" : ""
            let tripStatement = try database.statement(
                """
                SELECT t.id, t.external_id, t.short_name, t.headsign, r.short_name, r.long_name, r.route_type, a.name,
                       t.service_id, t.bikes_allowed, \(detail)
                FROM trip AS t
                JOIN route AS r ON r.id = t.route_id
                JOIN agency AS a ON a.id = r.agency_id
                \(join)
                WHERE t.pack_id = ?
                ORDER BY t.external_id
                """
            )
            try tripStatement.bind(pack.rowID, at: 1)
            var trips: [PlannerTrip] = []
            var tripIndexByRowID: [Int64: Int] = [:]
            while try tripStatement.step() {
                guard trips.count < maximumPlannerTrips else { throw SQLiteGTFSFailure.limitExceeded }
                guard let externalID = tripStatement.text(at: 1), !externalID.isEmpty,
                      let agency = tripStatement.text(at: 7)
                else { throw SQLiteGTFSFailure.invalidData }
                guard let mode = TimetableLegMode.of(routeType: tripStatement.int(at: 6), sourceID: pack.sourceID) else { continue }
                let shortName = trimmedNonEmpty(tripStatement.text(at: 2))
                let numericHeadsign = trimmedNonEmpty(tripStatement.text(at: 3)).flatMap { value in
                    value.unicodeScalars.allSatisfy({ ("0"..."9").contains($0) }) ? value : nil
                }
                let routeShort = trimmedNonEmpty(tripStatement.text(at: 4))
                let longName = trimmedNonEmpty(tripStatement.text(at: 5))
                let line = routeShort.map(TimetableNaming.lineLabel)
                let trenitaliaCode = routeShort.flatMap { raw -> String? in
                    let label = TimetableNaming.lineLabel(raw)
                    return label != raw ? label : nil
                }
                let category: JourneyTrainCategory = mode == .train
                    ? JourneyTrainCategory.of(trenitaliaCode: trenitaliaCode, names: [routeShort, longName, agency], isRail: true)
                    : .other
                let bikes = tripStatement.int(at: 9)
                tripIndexByRowID[tripStatement.int(at: 0)] = trips.count
                trips.append(PlannerTrip(
                    rowID: tripStatement.int(at: 0),
                    externalID: externalID,
                    publicNumber: shortName ?? numericHeadsign ?? routeShort ?? externalID,
                    hasTrainNumber: shortName != nil || numericHeadsign != nil,
                    lineLabel: line,
                    longName: longName,
                    operatorName: TimetableNaming.operatorName(agency, sourceID: pack.sourceID),
                    category: category,
                    mode: mode,
                    serviceRowID: tripStatement.int(at: 8),
                    blockID: nonEmpty(tripStatement.text(at: 10)),
                    wheelchair: PlannerWheelchair.of(tripStatement.optionalInt(at: 11)),
                    bikesAllowed: bikes == 1 ? true : bikes == 2 ? false : nil
                ))
            }
            // The stop times, trip by trip in stop order.
            let timeStatement = try database.statement(
                """
                SELECT st.trip_id, st.stop_id, st.arrival_seconds, st.departure_seconds, st.pickup_type, st.drop_off_type
                FROM trip AS t
                JOIN stop_time AS st ON st.trip_id = t.id
                WHERE t.pack_id = ?
                ORDER BY t.external_id, st.stop_sequence
                """
            )
            try timeStatement.bind(pack.rowID, at: 1)
            var stopIndex: [Int32] = []
            var arrival: [Int32] = []
            var departure: [Int32] = []
            var flags: [UInt8] = []
            var currentTrip = -1
            func minutes(_ column: Int32) -> Int32 {
                guard let seconds = timeStatement.optionalInt(at: column), seconds >= 0, seconds <= Int64(maximumGTFSSeconds) else { return -1 }
                return Int32(seconds / 60)
            }
            while try timeStatement.step() {
                guard stopIndex.count < PlannerCache.maximumStopTimes else { throw SQLiteGTFSFailure.limitExceeded }
                guard let trip = tripIndexByRowID[timeStatement.int(at: 0)] else { continue }
                if trip != currentTrip {
                    guard trips[trip].stopTimeCount == 0 else { throw SQLiteGTFSFailure.invalidData }
                    currentTrip = trip
                    trips[trip].firstStopTime = stopIndex.count
                }
                guard let stop = stops.indexByRowID[timeStatement.int(at: 1)] else { throw SQLiteGTFSFailure.invalidData }
                stopIndex.append(Int32(stop))
                arrival.append(minutes(2))
                departure.append(minutes(3))
                flags.append((timeStatement.int(at: 4) == 1 ? ConnectionScanTimetable.noPickup : 0)
                    | (timeStatement.int(at: 5) == 1 ? ConnectionScanTimetable.noDropOff : 0))
                trips[trip].stopTimeCount += 1
            }
            return PlannerPackTimetable(
                sourceID: pack.sourceID, trips: trips.filter { $0.stopTimeCount > 0 }, stopIndex: stopIndex, arrival: arrival,
                departure: departure, flags: flags, services: services
            )
        }
    }

    /// The connections of `pack` on `serviceDate` (yyyymmdd), from the cache.
    static func plannerDay(_ pack: PlannerPack, timetable: PlannerPackTimetable, serviceDate: Int, calendar: Calendar) throws -> PlannerDay {
        try PlannerCache.shared.day("\(pack.cacheKey)|\(serviceDate)") {
            guard let day = date(from: Int64(serviceDate), calendar: calendar) else { throw SQLiteGTFSFailure.invalidData }
            return PlannerDay.build(pack: timetable, serviceDay: day, serviceDate: serviceDate, calendar: calendar)
        }
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    private static func trimmedNonEmpty(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespaces), !trimmed.isEmpty else { return nil }
        return trimmed
    }
}
