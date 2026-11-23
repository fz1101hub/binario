import CSQLite
import Foundation

/// Read-only access to the versioned Binario static-GTFS SQLite artifact.
/// Invalid, corrupt, ambiguous, or route-less databases fail closed.
public struct SQLiteGTFSJourneyRepository: JourneyRepository, Sendable {
    public static let applicationID: Int32 = 0x424E_4754 // "BNGT"
    /// 2: `trip.bikes_allowed` (GTFS bikes_allowed: 1 allowed, 2 not allowed, NULL unknown).
    public static let userVersion: Int32 = 2
    public static let maximumResults = 8

    private static let expectedTables: [String: [String]] = [
        "metadata": ["key", "value"],
        "pack": ["id", "source_id", "attribution", "last_service_date"],
        "agency": ["id", "pack_id", "external_id", "name", "timezone"],
        "route": ["id", "pack_id", "agency_id", "external_id", "short_name", "long_name", "route_type"],
        "service": ["id", "pack_id", "external_id", "start_date", "day_count", "active_days"],
        "trip": ["id", "pack_id", "route_id", "service_id", "external_id", "headsign", "short_name", "direction_id", "bikes_allowed"],
        "stop": ["id", "pack_id", "external_id", "name", "latitude", "longitude", "catalog_station_id", "railway_code", "trenitalia_location_id"],
        "stop_time": ["trip_id", "stop_sequence", "stop_id", "arrival_seconds", "departure_seconds", "pickup_type", "drop_off_type"],
    ]
    private static let expectedIndices: Set<String> = [
        "idx_route_pack_external", "idx_service_pack_external", "idx_trip_pack_external",
        "idx_trip_route_service", "idx_stop_pack_external", "idx_stop_catalog_station",
        "idx_stop_time_stop_departure",
    ]
    private static let maximumResolvedStops = 128
    private static let maximumTimeZones = 16
    private static let maximumPacks = 64
    private static let maximumCoveredStations = 8_192
    private static let maximumCandidateRows = 4_096
    private static let maximumGTFSSeconds = 168 * 60 * 60 - 1
    private static let maximumServiceDayLookback = maximumGTFSSeconds / (24 * 60 * 60)
    /// «Partenze» reads the next service day only this far after the page's time: the trains just
    /// after midnight of a feed whose service day starts at 00:00 (Trenord, the Swiss pack).
    static let boardNextServiceDayHorizon: TimeInterval = 4 * 60 * 60
    private static let maximumServiceDays: Int64 = 3_660
    private static let maximumSourceLabelLength = 96

    public let databaseURL: URL
    /// The full foreign timetables installed on this iPhone (`ForeignScheduleStore`), by source:
    /// each is read through its own read-only connection with the same queries, and the base
    /// index no longer reads the trips of these packs (its border subset), so no train is listed
    /// twice. The files are never attached to one connection: their row identifiers overlap.
    public let foreignDatabaseURLs: [String: URL]

    public init(databaseURL: URL, foreignDatabaseURLs: [String: URL] = [:]) {
        self.databaseURL = databaseURL
        self.foreignDatabaseURLs = foreignDatabaseURLs
    }

    /// The installed foreign indexes in a fixed order, by source.
    private var foreignFiles: [ForeignFile] {
        var files: [ForeignFile] = []
        for sourceID in foreignDatabaseURLs.keys.sorted() {
            guard let url = foreignDatabaseURLs[sourceID] else { continue }
            files.append(ForeignFile(sourceID: sourceID, url: url))
        }
        return files
    }

    /// The installer's check: always the full validation, whose result the reads of this process
    /// then reuse (`validatedOnce`).
    static func validatesDatabase(at url: URL, inputFingerprint: String) -> Bool {
        do {
            let metadata = try validate(SQLiteDatabase(readOnlyURL: url))
            guard metadata["input_fingerprint_sha256"] == inputFingerprint else {
                validatedIndices.forget(url)
                return false
            }
            validatedIndices.record(metadata, for: url)
            return true
        } catch {
            validatedIndices.forget(url)
            return false
        }
    }

    /// `validatesDatabase`, and the index holds exactly one pack, `sourceID`: a downloaded per-country
    /// timetable (`BundledGTFSScheduleInstaller.installVerified`, `ForeignScheduleStore`).
    static func validatesDatabase(at url: URL, inputFingerprint: String, onlySourceID sourceID: String) -> Bool {
        guard validatesDatabase(at: url, inputFingerprint: inputFingerprint) else { return false }
        do {
            let database = try SQLiteDatabase(readOnlyURL: url)
            let statement = try database.statement("SELECT source_id FROM pack ORDER BY id LIMIT 2")
            var sources: [String] = []
            while try statement.step() {
                sources.append(statement.text(at: 0) ?? "")
            }
            guard sources == [sourceID] else {
                validatedIndices.forget(url)
                return false
            }
            return true
        } catch {
            validatedIndices.forget(url)
            return false
        }
    }

    /// The installed databases validated in this process: every search, board page and «Più tardi»
    /// opens the file again, and the schema checks and `quick_check` of the whole index (tens of
    /// milliseconds or more on a phone) need not run each time for the read-only file the installer
    /// has just validated. A file of another size is validated again; a failed read opens the index
    /// again through the installer (`AppModel`), whose validation is always full.
    private static let validatedIndices = ValidatedIndexCache()

    /// `validate`, once per process for a file of the same path and size.
    private static func validatedOnce(_ database: SQLiteDatabase, at url: URL) throws -> [String: String] {
        if let metadata = validatedIndices.metadata(for: url) { return metadata }
        let metadata = try validate(database)
        validatedIndices.record(metadata, for: url)
        return metadata
    }

    /// Every pack of the index with its attribution, last service day and the number of rail
    /// trips its filter retained (0 when a feed contributed nothing), in ingestion order.
    /// A full foreign timetable installed takes the place of its border subset, with its own
    /// attribution, validity and trip count.
    public func sourcePacks() throws -> [GTFSSourcePack] {
        var packs = try Self.sourcePacks(in: databaseURL)
        for file in foreignFiles {
            let foreign = try Self.sourcePacks(in: file.url)
            guard foreign.count == 1, let replacement = foreign.first, replacement.sourceID == file.sourceID else {
                throw SQLiteGTFSFailure.invalidData
            }
            if let index = packs.firstIndex(where: { $0.sourceID == file.sourceID }) {
                packs[index] = replacement
            } else {
                guard packs.count < Self.maximumPacks else { throw SQLiteGTFSFailure.limitExceeded }
                packs.append(replacement)
            }
        }
        return packs
    }

    private static func sourcePacks(in databaseURL: URL) throws -> [GTFSSourcePack] {
        let database = try SQLiteDatabase(readOnlyURL: databaseURL)
        _ = try Self.validatedOnce(database, at: databaseURL)
        let calendar = Self.serviceCalendar(timeZone: TimeZone(identifier: "Europe/Rome") ?? .current)
        let statement = try database.statement(
            """
            SELECT p.source_id, p.attribution, p.last_service_date,
                   (SELECT count(*) FROM trip AS t WHERE t.pack_id = p.id)
            FROM pack AS p ORDER BY p.id LIMIT ?
            """
        )
        try statement.bind(Int64(Self.maximumPacks + 1), at: 1)
        var packs: [GTFSSourcePack] = []
        while try statement.step() {
            guard packs.count < Self.maximumPacks else { throw SQLiteGTFSFailure.limitExceeded }
            guard let sourceID = statement.text(at: 0)?.nonEmpty,
                  let attribution = statement.text(at: 1)?.normalizedWhitespace.nonEmpty,
                  let lastServiceDay = Self.date(from: statement.int(at: 2), calendar: calendar)
            else { throw SQLiteGTFSFailure.invalidData }
            packs.append(GTFSSourcePack(
                sourceID: sourceID,
                attribution: attribution,
                // Noon keeps the calendar day stable when the date is formatted in any time zone.
                lastServiceDate: lastServiceDay.addingTimeInterval(12 * 60 * 60),
                tripCount: Int(statement.int(at: 3))
            ))
        }
        guard !packs.isEmpty else { throw SQLiteGTFSFailure.invalidData }
        return packs
    }

    /// The catalogue station identities that at least one stop of the index is bound to: the
    /// only stations for which a search can find trains at all.
    public func coveredCatalogStationIDs() throws -> Set<String> {
        var identifiers = try Self.coveredCatalogStationIDs(in: databaseURL)
        for file in foreignFiles {
            identifiers.formUnion(try Self.coveredCatalogStationIDs(in: file.url))
            guard identifiers.count <= Self.maximumCoveredStations else { throw SQLiteGTFSFailure.limitExceeded }
        }
        return identifiers
    }

    private static func coveredCatalogStationIDs(in databaseURL: URL) throws -> Set<String> {
        let database = try SQLiteDatabase(readOnlyURL: databaseURL)
        _ = try Self.validatedOnce(database, at: databaseURL)
        let statement = try database.statement(
            "SELECT DISTINCT catalog_station_id FROM stop WHERE catalog_station_id IS NOT NULL LIMIT ?"
        )
        try statement.bind(Int64(Self.maximumCoveredStations + 1), at: 1)
        var identifiers = Set<String>()
        while try statement.step() {
            guard identifiers.count < Self.maximumCoveredStations else { throw SQLiteGTFSFailure.limitExceeded }
            guard let identifier = statement.text(at: 0)?.nonEmpty else { throw SQLiteGTFSFailure.invalidData }
            identifiers.insert(identifier)
        }
        return identifiers
    }

    public func journeys(for query: JourneyQuery) async -> JourneyResult {
        do {
            return .available(try Self.loadAll(databaseURL: databaseURL, foreignFiles: foreignFiles, query: query))
        } catch SQLiteGTFSFailure.noRoute {
            return .unavailable(.noJourney)
        } catch SQLiteGTFSFailure.scheduleExpired {
            return .unavailable(.scheduleExpired)
        } catch {
            return .unavailable(.providerInvalid)
        }
    }

    /// The first trains of the base index and of every installed foreign index, merged by time
    /// and bounded at `maximumResults`. Only the base index alone fails as it always did; with
    /// foreign files, a file that cannot answer is left out while another gives trains, and the
    /// search fails only when none does: a damaged file first, then an ended timetable, else no train.
    private static func loadAll(databaseURL: URL, foreignFiles: [ForeignFile], query: JourneyQuery) throws -> JourneySnapshot {
        var selections: [JourneySelection] = []
        var failures: [any Error] = []
        do {
            selections.append(try load(databaseURL: databaseURL, query: query, excluded: foreignFiles.map(\.sourceID)))
        } catch {
            guard !foreignFiles.isEmpty else { throw error }
            failures.append(error)
        }
        for file in foreignFiles {
            do {
                selections.append(try load(databaseURL: file.url, query: query, excluded: []))
            } catch {
                failures.append(error)
            }
        }
        var candidates: [Candidate] = []
        var observedAt: Date?
        for selection in selections {
            candidates += selection.candidates
            observedAt = min(observedAt ?? selection.observedAt, selection.observedAt)
        }
        // The base index says whether a national pack serves both ends (the foreign files have none).
        let selected = firstResults(candidates, border: selections.first?.border ?? true)
        guard !selected.isEmpty, let sourceObservedAt = observedAt else { throw combinedFailure(failures) }

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

    /// Why no file gave a train: a damaged or unreadable file (the index is opened again), then
    /// an ended timetable, else no train.
    private static func combinedFailure(_ failures: [any Error]) -> any Error {
        var expired = false
        for failure in failures {
            guard let known = failure as? SQLiteGTFSFailure else { return failure }
            switch known {
            case .noRoute:
                continue
            case .scheduleExpired:
                expired = true
            case .invalidDatabase, .invalidSchema, .invalidData, .limitExceeded:
                return known
            }
        }
        return expired ? SQLiteGTFSFailure.scheduleExpired : SQLiteGTFSFailure.noRoute
    }

    /// The departure order of the results, then the number, then the identifier.
    private static func precedes(_ first: Candidate, _ second: Candidate) -> Bool {
        if first.departure != second.departure { return first.departure < second.departure }
        if first.number != second.number { return first.number < second.number }
        return first.id < second.id
    }

    /// The first `maximumResults` trains in departure order, each once: a train two packs publish
    /// is listed from the preferred one (`TimetableCopies.kept`; `border` when no national pack
    /// serves an end of the search).
    private static func firstResults(_ candidates: [Candidate], border: Bool) -> [Candidate] {
        let kept = TimetableCopies.kept(candidates.map(\.run), border: border).map { candidates[$0] }
        let sorted = kept.sorted(by: precedes)
        var result: [Candidate] = []
        for candidate in sorted {
            guard result.count < maximumResults else { break }
            guard !result.contains(where: { $0.id == candidate.id }) else { continue }
            result.append(candidate)
        }
        return result
    }

    /// The first trains of one file, with the day its source was generated. `excluded`: the packs
    /// whose trips another file answers for (the base index with full foreign timetables installed).
    private static func load(databaseURL: URL, query: JourneyQuery, excluded: [String]) throws -> JourneySelection {
        let database = try SQLiteDatabase(readOnlyURL: databaseURL)
        database.excludedSourceIDs = excluded
        let metadata = try validatedOnce(database, at: databaseURL)
        guard let sourceGeneratedAt = metadata["source_generated_at"],
              let sourceObservedAt = parseSourceDate(sourceGeneratedAt)
        else { throw SQLiteGTFSFailure.invalidData }
        let timeZones = try agencyTimeZones(database)
        let referenceTimeZone = timeZones.first ?? TimeZone(identifier: "Europe/Rome") ?? .current
        // After the last day any included pack runs, every search needs an app update: report
        // the expiry even for stations the index never served instead of "no data".
        if try indexEnded(before: query.departure, timeZone: referenceTimeZone, database: database) {
            throw SQLiteGTFSFailure.scheduleExpired
        }
        let originIDs = try resolve(query.origin, database: database)
        let destinationIDs = try resolve(query.destination, database: database)
        guard Set(originIDs).isDisjoint(with: destinationIDs) else { throw SQLiteGTFSFailure.noRoute }
        let nationalAtOrigin = try nationalPackServes(originIDs, database: database)
        let nationalAtDestination = try nationalPackServes(destinationIDs, database: database)
        let border = !nationalAtOrigin || !nationalAtDestination

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
        var inWindow: [Candidate] = []
        for candidate in candidates where candidate.departure >= query.departure && candidate.departure <= upperBound {
            inWindow.append(candidate)
        }
        let selected = firstResults(inWindow, border: border)
        guard !selected.isEmpty else {
            if try packsEnded(
                before: query.departure,
                originIDs: originIDs,
                destinationIDs: destinationIDs,
                timeZone: referenceTimeZone,
                database: database
            ) {
                throw SQLiteGTFSFailure.scheduleExpired
            }
            throw SQLiteGTFSFailure.noRoute
        }
        return JourneySelection(candidates: selected, observedAt: sourceObservedAt, border: border)
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

    /// True when the last service day of every pack that holds trips is before the requested
    /// day: nothing in the index can answer, whatever the stations.
    private static func indexEnded(before departure: Date, timeZone: TimeZone, database: SQLiteDatabase) throws -> Bool {
        let statement = try database.statement(
            """
            SELECT COALESCE(MAX(p.last_service_date), 0) FROM pack AS p
            WHERE EXISTS (SELECT 1 FROM trip AS t WHERE t.pack_id = p.id)\(database.excludedPacksClause("p.source_id"))
            """
        )
        var binding: Int32 = 1
        try database.bindExcludedPacks(statement, from: &binding)
        guard try statement.step() else { return false }
        let lastServiceDate = statement.int(at: 0)
        guard lastServiceDate > 0 else { return false }
        let requestedDay = Int64(encodedDate(departure, calendar: serviceCalendar(timeZone: timeZone)))
        return lastServiceDate < requestedDay
    }

    /// True when every pack that holds both stations has its last service day before the
    /// requested day: the user needs an app update, not a different search.
    private static func packsEnded(
        before departure: Date,
        originIDs: [Int64],
        destinationIDs: [Int64],
        timeZone: TimeZone,
        database: SQLiteDatabase
    ) throws -> Bool {
        let originPlaceholders = Array(repeating: "?", count: originIDs.count).joined(separator: ",")
        let destinationPlaceholders = Array(repeating: "?", count: destinationIDs.count).joined(separator: ",")
        let statement = try database.statement(
            """
            SELECT COALESCE(MAX(p.last_service_date), 0) FROM pack AS p
            WHERE p.id IN (SELECT pack_id FROM stop WHERE id IN (\(originPlaceholders)))
              AND p.id IN (SELECT pack_id FROM stop WHERE id IN (\(destinationPlaceholders)))\(database.excludedPacksClause("p.source_id"))
            """
        )
        var binding: Int32 = 1
        for id in originIDs { try statement.bind(id, at: binding); binding += 1 }
        for id in destinationIDs { try statement.bind(id, at: binding); binding += 1 }
        try database.bindExcludedPacks(statement, from: &binding)
        guard try statement.step() else { return false }
        let lastServiceDate = statement.int(at: 0)
        guard lastServiceDate > 0 else { return false }
        let requestedDay = Int64(encodedDate(departure, calendar: serviceCalendar(timeZone: timeZone)))
        return lastServiceDate < requestedDay
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
                   svc.start_date, svc.day_count, svc.active_days, t.bikes_allowed,
                   origin_stop.name, COALESCE(destination_time.arrival_seconds, destination_time.departure_seconds)
            FROM stop_time AS origin_time
            JOIN stop_time AS destination_time
              ON destination_time.trip_id = origin_time.trip_id
             AND destination_time.stop_sequence > origin_time.stop_sequence
            JOIN stop AS origin_stop ON origin_stop.id = origin_time.stop_id
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
              AND COALESCE(origin_time.departure_seconds, origin_time.arrival_seconds) BETWEEN 0 AND ?\(database.excludedPacksClause("p.source_id"))
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
        try database.bindExcludedPacks(statement, from: &binding)
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
            let number = trainNumber(trip: statement.text(at: 2), route: statement.text(at: 4), externalTripID: externalTripID)
            let operatorName = bounded(TimetableNaming.operatorName(agencyName, sourceID: sourceID), maximum: 64)
            let category = trainCategory(route: statement.text(at: 4), longName: statement.text(at: 5), type: statement.int(at: 6))
            let destination = bounded(destinationName, maximum: 96)
            guard !number.isEmpty, !operatorName.isEmpty, !category.isEmpty, !destination.isEmpty,
                  let vehicle = timetableVehicle(routeType: statement.int(at: 6), sourceID: sourceID, operatorName: operatorName, route: statement.text(at: 4))
            else {
                throw SQLiteGTFSFailure.invalidData
            }
            // The arrival at the searched destination: two packs' copies of one train leave and arrive together.
            let arrivalSeconds = statement.int(at: 18)
            var arrival: Date?
            if arrivalSeconds >= seconds, arrivalSeconds <= Int64(maximumGTFSSeconds) {
                arrival = try gtfsDate(serviceDay: serviceDay, seconds: TimeInterval(arrivalSeconds), calendar: calendar)
            }
            let formattedTime = timeString(departure, timeZone: calendar.timeZone)
            let bikesAllowed = bikes(statement.int(at: 16))
            let serviceDate = encodedDate(serviceDay, calendar: calendar)
            let id = "\(sourceID):\(externalTripID):\(serviceDate):\(seconds)"
            candidates.append(Candidate(
                id: id,
                number: number,
                departure: departure,
                attribution: attribution,
                run: TimetableCopies.Run(
                    sourceID: sourceID, number: number, operatorName: operatorName, departure: departure, arrival: arrival,
                    originName: statement.text(at: 17) ?? "", destinationName: destination
                ),
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
                    delayMinutes: 0,
                    bikesAllowed: bikesAllowed,
                    hasTrainNumber: statement.text(at: 2)?.nonEmpty != nil,
                    vehicle: vehicle
                )
            ))
        }
        return candidates
    }

    /// The number a train is listed with: the trip's published number, else its route's short
    /// name, else the trip's identifier (`TrainService.hasTrainNumber` is then false). With `trainCategory`, the naming of the planner rows and of
    /// «Partenze» (`ReliableAlternatives.trainLabel` joins the two).
    private static func trainNumber(trip tripShortName: String?, route routeShortName: String?, externalTripID: String) -> String {
        let raw: String = tripShortName?.nonEmpty ?? routeShortName?.nonEmpty ?? externalTripID
        return bounded(raw, maximum: 24)
    }

    /// The route's short name as Android shows it («FR» for Trenitalia's «0083_FR»,
    /// `TimetableNaming.lineLabel`), else its long name, else its GTFS route type.
    private static func trainCategory(route routeShortName: String?, longName: String?, type routeType: Int64) -> String {
        let line = routeShortName.map(TimetableNaming.lineLabel)?.nonEmpty
        let raw: String = line ?? longName?.nonEmpty ?? "GTFS \(routeType)"
        return bounded(raw, maximum: 64)
    }

    /// What a service travels on (`TimetableLegMode`, `TimetableVehicle.of`), from its route type,
    /// its pack, its operator and its line; nil for a route type the index never keeps.
    private static func timetableVehicle(routeType: Int64, sourceID: String, operatorName: String, route routeShortName: String?) -> TimetableVehicle? {
        guard let mode = TimetableLegMode.of(routeType: routeType, sourceID: sourceID) else { return nil }
        return TimetableVehicle.of(mode: mode, operatorName: operatorName, lineLabel: routeShortName.map(TimetableNaming.lineLabel))
    }

    /// True when a stop of `stopIDs` belongs to a national pack (`TimetableCopies.nationalPacks`):
    /// otherwise the station is a border one and the foreign packs' copy of a train is kept.
    private static func nationalPackServes(_ stopIDs: [Int64], database: SQLiteDatabase) throws -> Bool {
        guard !stopIDs.isEmpty else { return false }
        let placeholders = Array(repeating: "?", count: stopIDs.count).joined(separator: ",")
        let statement = try database.statement(
            "SELECT DISTINCT p.source_id FROM stop AS s JOIN pack AS p ON p.id = s.pack_id WHERE s.id IN (\(placeholders))"
        )
        var binding: Int32 = 1
        for id in stopIDs { try statement.bind(id, at: binding); binding += 1 }
        while try statement.step() {
            if let sourceID = statement.text(at: 0), TimetableCopies.nationalPacks.contains(sourceID) { return true }
        }
        return false
    }

    /// Only a published value: 1 allowed, 2 not allowed; NULL (empty, 0 or a pack whose value is
    /// not shown, see BIKES_VALUE_NOT_SHOWN in the builder) stays unknown.
    private static func bikes(_ value: Int64) -> Bool? {
        switch value {
        case 1: return true
        case 2: return false
        default: return nil
        }
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
        let run: TimetableCopies.Run
        let service: TrainService
    }

    /// The first trains of one file, the day its source was generated, and whether no national
    /// pack of the file serves an end of the search (`TimetableCopies.rank`).
    private struct JourneySelection {
        let candidates: [Candidate]
        let observedAt: Date
        let border: Bool
    }
}

// MARK: - «Partenze»

/// The bundled timetable as the first `StationBoardProvider`, and the fallback of any live one.
extension SQLiteGTFSJourneyRepository: StationBoardProvider {
    /// The trains leaving `query.station` from `query.from`, read from the index as the planner
    /// reads its results: the station's stops, boarding allowed there (`pickup_type = 0`), the
    /// service days the trips run on, the planner's naming and bikes, nothing live. A page holds the
    /// trains of today's service day of each agency time zone, the runs of earlier service days
    /// still to leave (yesterday's after midnight, GTFS times from 24:00) and the trains of the next
    /// service day leaving within `boardNextServiceDayHorizon` of `query.from` (a feed whose day
    /// starts at midnight lists its night trains there); a first page without any of them gives
    /// `firstDepartureNextDay`, the next service day's first train.
    /// A trip whose last stop is the station itself (it only arrives there) is no departure, and a
    /// trip calling twice is listed once, at its first departure from `query.from`. Coaches and
    /// replacement buses follow the planner's rule: the index keeps the rail and bus routes of the
    /// packs (tools/build_ios_gtfs_schedule.py, INDEXED_ROUTE_TYPES), a bus is listed with its
    /// vehicle (`TrainService.vehicle`), and a train two packs publish is listed once (`boardOrder`).
    public func departures(for query: StationBoardQuery) async -> StationBoardResult {
        do {
            return .available(try Self.loadBoards(databaseURL: databaseURL, foreignFiles: foreignFiles, query: query))
        } catch SQLiteGTFSFailure.noRoute {
            return .unavailable(.noJourney)
        } catch SQLiteGTFSFailure.scheduleExpired {
            return .unavailable(.scheduleExpired)
        } catch {
            return .unavailable(.providerInvalid)
        }
    }

    /// The board of the base index and of every installed foreign index, merged by departure:
    /// the first `query.limit` trains, «primo domani» only when none is listed, the feeds of the
    /// trains shown. As for the planner, only the base index alone fails as it always did; with
    /// foreign files a file that cannot answer is left out while another answers (one whose
    /// timetables of the station have ended then says so with `someTimetablesEnded`).
    private static func loadBoards(databaseURL: URL, foreignFiles: [ForeignFile], query: StationBoardQuery) throws -> StationBoardPage {
        var selections: [BoardSelection] = []
        var failures: [any Error] = []
        do {
            selections.append(try loadBoard(databaseURL: databaseURL, query: query, excluded: foreignFiles.map(\.sourceID)))
        } catch {
            guard !foreignFiles.isEmpty else { throw error }
            failures.append(error)
        }
        for file in foreignFiles {
            do {
                selections.append(try loadBoard(databaseURL: file.url, query: query, excluded: []))
            } catch {
                failures.append(error)
            }
        }
        guard !selections.isEmpty else { throw combinedFailure(failures) }

        var candidates: [StationBoardCandidate] = []
        var someEnded = false
        for selection in selections {
            candidates += selection.ordered
            if selection.someEnded { someEnded = true }
        }
        for failure in failures where (failure as? SQLiteGTFSFailure) == .scheduleExpired {
            someEnded = true
        }
        // The base index says whether a national pack serves the station (the foreign files have none).
        let ordered = boardOrder(candidates, border: selections.first?.border ?? true)
        let listed = Array(ordered.prefix(query.limit))
        var firstNextDay: StationBoardCandidate?
        if listed.isEmpty {
            for selection in selections {
                guard let candidate = selection.firstNextDay else { continue }
                if firstNextDay.map({ candidate.entry.cursor < $0.entry.cursor }) ?? true {
                    firstNextDay = candidate
                }
            }
        }
        let shown: [StationBoardCandidate] = listed + (firstNextDay.map { [$0] } ?? [])
        return StationBoardPage(
            query: query,
            departures: listed.map(\.entry),
            hasMore: ordered.count > query.limit,
            firstDepartureNextDay: firstNextDay?.entry,
            attributions: shown.map(\.attribution).uniqued().map { String($0.prefix(96)) },
            someTimetablesEnded: someEnded
        )
    }

    /// The trains of one file from `query.from`, in departure order, each once; its first train of
    /// the next service day when none is left today; whether a feed of the station has ended.
    private static func loadBoard(databaseURL: URL, query: StationBoardQuery, excluded: [String]) throws -> BoardSelection {
        let database = try SQLiteDatabase(readOnlyURL: databaseURL)
        database.excludedSourceIDs = excluded
        _ = try validatedOnce(database, at: databaseURL)
        let timeZones = try agencyTimeZones(database)
        let referenceTimeZone = timeZones.first ?? TimeZone(identifier: "Europe/Rome") ?? .current
        // As for the planner: after the last day of every pack the board needs an app update.
        if try indexEnded(before: query.from, timeZone: referenceTimeZone, database: database) {
            throw SQLiteGTFSFailure.scheduleExpired
        }
        let stopIDs = try resolve(query.station, database: database)
        let border = try !nationalPackServes(stopIDs, database: database)
        // One more than the page tells whether «Più tardi» has anything to add; twice as many, as a
        // train two packs publish is listed once (`boardOrder`).
        let wanted = 2 * (query.limit + 1)

        var candidates: [StationBoardCandidate] = []
        for timeZone in timeZones {
            let calendar = serviceCalendar(timeZone: timeZone)
            let today = calendar.startOfDay(for: query.from)
            // The first hours of the next service day, today's, then the earlier ones, whose runs
            // past 24:00 can still leave.
            for offset in -1...maximumServiceDayLookback {
                guard let serviceDay = calendar.date(byAdding: .day, value: -offset, to: today) else {
                    throw SQLiteGTFSFailure.invalidData
                }
                candidates += try boardCandidates(
                    database: database,
                    query: query,
                    stopIDs: stopIDs,
                    agencyTimeZone: timeZone.identifier,
                    serviceDay: serviceDay,
                    calendar: calendar,
                    wanted: wanted,
                    latest: offset < 0 ? query.from.addingTimeInterval(boardNextServiceDayHorizon) : nil
                )
            }
        }
        let ordered = boardOrder(candidates, border: border)
        let listed = Array(ordered.prefix(query.limit))

        var firstNextDay: StationBoardCandidate?
        if listed.isEmpty {
            if try stationPacksEnded(before: query.from, stopIDs: stopIDs, timeZone: referenceTimeZone, database: database) {
                throw SQLiteGTFSFailure.scheduleExpired
            }
            if query.after == nil {
                firstNextDay = try firstDepartureOfNextServiceDay(
                    database: database,
                    query: query,
                    stopIDs: stopIDs,
                    timeZones: timeZones
                )
            }
        }
        // Decided per feed: one ended while another goes on still says why trains are missing.
        let someEnded = try stationPackEnded(before: query.from, stopIDs: stopIDs, timeZone: referenceTimeZone, database: database)
        return BoardSelection(ordered: ordered, firstNextDay: firstNextDay, someEnded: someEnded, border: border)
    }

    /// `candidates` in board order, each departure once and each train once: a train two packs
    /// publish leaving at the same minute for the same terminus is listed from the preferred pack
    /// (`TimetableCopies.keptOnBoard`; `border` when no national pack serves the station).
    private static func boardOrder(_ candidates: [StationBoardCandidate], border: Bool) -> [StationBoardCandidate] {
        var seen = Set<String>()
        let unique = candidates
            .sorted { $0.entry.cursor < $1.entry.cursor }
            .filter { seen.insert($0.entry.id).inserted }
        let kept = Set(TimetableCopies.keptOnBoard(unique.map(\.run), border: border))
        return unique.indices.filter { kept.contains($0) }.map { unique[$0] }
    }

    /// «Primo domani alle …»: the first train of the service day after today's, over every agency
    /// time zone.
    private static func firstDepartureOfNextServiceDay(
        database: SQLiteDatabase,
        query: StationBoardQuery,
        stopIDs: [Int64],
        timeZones: [TimeZone]
    ) throws -> StationBoardCandidate? {
        var first: StationBoardCandidate?
        for timeZone in timeZones {
            let calendar = serviceCalendar(timeZone: timeZone)
            guard let nextDay = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: query.from)) else {
                throw SQLiteGTFSFailure.invalidData
            }
            let found = try boardCandidates(
                database: database,
                query: query,
                stopIDs: stopIDs,
                agencyTimeZone: timeZone.identifier,
                serviceDay: nextDay,
                calendar: calendar,
                wanted: 1
            )
            for candidate in found where first.map({ candidate.entry.cursor < $0.entry.cursor }) ?? true {
                first = candidate
            }
        }
        return first
    }

    /// The trains of `serviceDay` leaving the station's stops from `query.from`, and after its
    /// cursor, in departure order: at least `wanted` of them when the day has as many, with every
    /// train of the same second as the last one, so that the first trains of all the service days
    /// merged are exact. At most `maximumCandidateRows` rows are read, as by the planner. With
    /// `latest`, only the trains leaving at or before it.
    private static func boardCandidates(
        database: SQLiteDatabase,
        query: StationBoardQuery,
        stopIDs: [Int64],
        agencyTimeZone: String,
        serviceDay: Date,
        calendar: Calendar,
        wanted: Int,
        latest: Date? = nil
    ) throws -> [StationBoardCandidate] {
        // The GTFS second of `query.from` on this service day: earlier runs have left.
        let base = try gtfsDate(serviceDay: serviceDay, seconds: 0, calendar: calendar)
        let earliest: Double = query.from.timeIntervalSince(base).rounded(.up)
        guard earliest <= Double(maximumGTFSSeconds) else { return [] }
        let lowerSeconds: Int64 = earliest <= 0 ? 0 : Int64(earliest)
        var upperSeconds = Int64(maximumGTFSSeconds)
        if let latest {
            let last: Double = latest.timeIntervalSince(base).rounded(.down)
            guard last >= Double(lowerSeconds) else { return [] }
            upperSeconds = min(upperSeconds, Int64(min(last, Double(maximumGTFSSeconds))))
        }
        let placeholders = Array(repeating: "?", count: stopIDs.count).joined(separator: ",")
        // The builder stores arrival and departure both or neither, so departure_seconds is the
        // planner's COALESCE and the (stop_id, departure_seconds) index bounds the read.
        let statement = try database.statement(
            """
            SELECT t.id, t.external_id, t.short_name,
                   r.short_name, r.long_name, r.route_type,
                   a.name, a.timezone,
                   board_time.departure_seconds, board_time.stop_sequence,
                   p.source_id, p.attribution,
                   svc.start_date, svc.day_count, svc.active_days, t.bikes_allowed
            FROM stop_time AS board_time
            JOIN trip AS t ON t.id = board_time.trip_id
            JOIN route AS r ON r.id = t.route_id
            JOIN agency AS a ON a.id = r.agency_id
            JOIN pack AS p ON p.id = t.pack_id
            JOIN service AS svc ON svc.id = t.service_id
            WHERE board_time.stop_id IN (\(placeholders))
              AND board_time.pickup_type = 0
              AND a.timezone = ?
              AND board_time.departure_seconds BETWEEN ? AND ?\(database.excludedPacksClause("p.source_id"))
            ORDER BY board_time.departure_seconds, t.id, board_time.stop_sequence
            LIMIT ?
            """
        )
        var binding: Int32 = 1
        for id in stopIDs { try statement.bind(id, at: binding); binding += 1 }
        try statement.bind(agencyTimeZone, at: binding); binding += 1
        try statement.bind(lowerSeconds, at: binding); binding += 1
        try statement.bind(upperSeconds, at: binding); binding += 1
        try database.bindExcludedPacks(statement, from: &binding)
        try statement.bind(Int64(maximumCandidateRows + 1), at: binding)

        let boardStops = Set(stopIDs)
        var seenTrips = Set<Int64>()
        var candidates: [StationBoardCandidate] = []
        var rowsRead = 0
        while try statement.step() {
            rowsRead += 1
            guard rowsRead <= maximumCandidateRows else { throw SQLiteGTFSFailure.limitExceeded }
            let seconds = statement.int(at: 8)
            // Enough trains, and none later can come before them.
            if candidates.count >= wanted, let last = candidates.last, seconds > last.seconds { break }
            let tripID = statement.int(at: 0)
            guard
                let externalTripID = statement.text(at: 1), !externalTripID.isEmpty,
                let agencyName = statement.text(at: 6), !agencyName.isEmpty,
                statement.text(at: 7) == agencyTimeZone,
                let sourceID = statement.text(at: 10), !sourceID.isEmpty,
                let attribution = statement.text(at: 11)?.normalizedWhitespace, !attribution.isEmpty,
                let activeDays = statement.blob(at: 14)
            else { throw SQLiteGTFSFailure.invalidData }
            let startDate = statement.int(at: 12)
            let dayCount = statement.int(at: 13)
            guard
                seconds >= 0, seconds <= Int64(maximumGTFSSeconds),
                !seenTrips.contains(tripID),
                serviceRuns(serviceDay: serviceDay, startDate: startDate, dayCount: dayCount,
                            activeDays: activeDays, calendar: calendar)
            else { continue }
            let departure = try gtfsDate(serviceDay: serviceDay, seconds: TimeInterval(seconds), calendar: calendar)
            guard departure >= query.from else { continue }
            // A trip calling at the station twice is listed at its first departure only.
            seenTrips.insert(tripID)
            let number = trainNumber(trip: statement.text(at: 2), route: statement.text(at: 3), externalTripID: externalTripID)
            let serviceDate = encodedDate(serviceDay, calendar: calendar)
            let id = "\(sourceID):\(externalTripID):\(serviceDate):\(seconds)"
            if let after = query.after, !(after < StationBoardCursor(departure: departure, number: number, id: id)) {
                continue
            }
            guard let terminus = try lastStop(database: database, tripID: tripID) else {
                throw SQLiteGTFSFailure.invalidData
            }
            // A trip ending at the station (it only arrives there) is no departure.
            let boardSequence = statement.int(at: 9)
            guard terminus.sequence > boardSequence,
                  !boardStops.contains(terminus.stopID),
                  terminus.catalogStationID != query.station.id
            else { continue }
            let operatorName = bounded(TimetableNaming.operatorName(agencyName, sourceID: sourceID), maximum: 64)
            let category = trainCategory(route: statement.text(at: 3), longName: statement.text(at: 4), type: statement.int(at: 5))
            let destination = bounded(terminus.name, maximum: 96)
            guard !number.isEmpty, !operatorName.isEmpty, !category.isEmpty, !destination.isEmpty,
                  let vehicle = timetableVehicle(routeType: statement.int(at: 5), sourceID: sourceID, operatorName: operatorName, route: statement.text(at: 3))
            else {
                throw SQLiteGTFSFailure.invalidData
            }
            let formattedTime = timeString(departure, timeZone: calendar.timeZone)
            let service = TrainService(
                id: id,
                number: number,
                operatorName: operatorName,
                category: category,
                destination: destination,
                scheduledDeparture: formattedTime,
                expectedDeparture: formattedTime,
                status: OperationalState.programmed.rawValue,
                platform: nil,
                delayMinutes: 0,
                bikesAllowed: bikes(statement.int(at: 15)),
                hasTrainNumber: statement.text(at: 2)?.nonEmpty != nil,
                vehicle: vehicle
            )
            candidates.append(StationBoardCandidate(
                entry: StationBoardDeparture(
                    departure: departure,
                    service: service,
                    terminus: terminusIdentity(terminus, sourceID: sourceID, name: destination)
                ),
                seconds: seconds,
                attribution: attribution,
                run: TimetableCopies.Run(
                    sourceID: sourceID, number: number, operatorName: operatorName, departure: departure, arrival: nil,
                    originName: query.station.name, destinationName: destination, destinationStationID: terminus.catalogStationID
                )
            ))
        }
        return candidates
    }

    /// The last stop of a trip, by stop sequence (the primary key of `stop_time`).
    private static func lastStop(database: SQLiteDatabase, tripID: Int64) throws -> TripLastStop? {
        let statement = try database.statement(
            """
            SELECT last_time.stop_id, last_time.stop_sequence, last_stop.name, last_stop.external_id,
                   last_stop.catalog_station_id, last_stop.railway_code
            FROM stop_time AS last_time
            JOIN stop AS last_stop ON last_stop.id = last_time.stop_id
            WHERE last_time.trip_id = ?
            ORDER BY last_time.stop_sequence DESC
            LIMIT 1
            """
        )
        try statement.bind(tripID, at: 1)
        guard try statement.step() else { return nil }
        guard let name = statement.text(at: 2)?.normalizedWhitespace, !name.isEmpty,
              let externalID = statement.text(at: 3), !externalID.isEmpty
        else { throw SQLiteGTFSFailure.invalidData }
        return TripLastStop(
            stopID: statement.int(at: 0),
            sequence: statement.int(at: 1),
            name: name,
            externalID: externalID,
            catalogStationID: statement.text(at: 4)?.nonEmpty,
            railwayCode: statement.text(at: 5)?.nonEmpty
        )
    }

    /// The catalogue identity of a last stop, or, for a stop no catalogue station is bound to, one
    /// made of its pack and its own identifier: it is only the destination of the train detail
    /// (measured at the train's terminus) and is never looked up in the catalogue.
    private static func terminusIdentity(_ stop: TripLastStop, sourceID: String, name: String) -> StationIdentity {
        if let catalogStationID = stop.catalogStationID {
            return StationIdentity(id: catalogStationID, name: name, railwayCode: stop.railwayCode)
        }
        let identifier = "gtfs:\(sourceID):\(stop.externalID)".normalizedWhitespace
        return StationIdentity(id: String(identifier.prefix(128)), name: name)
    }

    /// True when every pack with a stop of the station has its last service day before the day of
    /// `date`: the station's board needs an app update, not another time.
    private static func stationPacksEnded(
        before date: Date,
        stopIDs: [Int64],
        timeZone: TimeZone,
        database: SQLiteDatabase
    ) throws -> Bool {
        let placeholders = Array(repeating: "?", count: stopIDs.count).joined(separator: ",")
        let statement = try database.statement(
            """
            SELECT COALESCE(MAX(p.last_service_date), 0) FROM pack AS p
            WHERE p.id IN (SELECT pack_id FROM stop WHERE id IN (\(placeholders)))\(database.excludedPacksClause("p.source_id"))
            """
        )
        var binding: Int32 = 1
        for id in stopIDs { try statement.bind(id, at: binding); binding += 1 }
        try database.bindExcludedPacks(statement, from: &binding)
        guard try statement.step() else { return false }
        let lastServiceDate = statement.int(at: 0)
        guard lastServiceDate > 0 else { return false }
        let requestedDay = Int64(encodedDate(date, calendar: serviceCalendar(timeZone: timeZone)))
        return lastServiceDate < requestedDay
    }

    /// True when at least one pack with a stop of the station has its last service day before
    /// the requested day (`stationPacksEnded` asks whether all have).
    private static func stationPackEnded(
        before date: Date,
        stopIDs: [Int64],
        timeZone: TimeZone,
        database: SQLiteDatabase
    ) throws -> Bool {
        let placeholders = Array(repeating: "?", count: stopIDs.count).joined(separator: ",")
        let statement = try database.statement(
            """
            SELECT COUNT(*) FROM pack AS p
            WHERE p.id IN (SELECT pack_id FROM stop WHERE id IN (\(placeholders)))
              AND p.last_service_date > 0 AND p.last_service_date < ?\(database.excludedPacksClause("p.source_id"))
            """
        )
        let requestedDay = Int64(encodedDate(date, calendar: serviceCalendar(timeZone: timeZone)))
        var binding: Int32 = 1
        for id in stopIDs { try statement.bind(id, at: binding); binding += 1 }
        try statement.bind(requestedDay, at: binding); binding += 1
        try database.bindExcludedPacks(statement, from: &binding)
        guard try statement.step() else { return false }
        return statement.int(at: 0) > 0
    }
}

/// The metadata of the databases validated in this process, by path and size
/// (`SQLiteGTFSJourneyRepository.validatedOnce`). Process memory only.
private final class ValidatedIndexCache: @unchecked Sendable {
    private struct Entry {
        let size: Int
        let metadata: [String: String]
    }

    private let lock = NSLock()
    private var entries: [String: Entry] = [:]

    func metadata(for url: URL) -> [String: String]? {
        guard let size = Self.size(of: url) else { return nil }
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries[Self.key(url)], entry.size == size else { return nil }
        return entry.metadata
    }

    func record(_ metadata: [String: String], for url: URL) {
        guard let size = Self.size(of: url) else { return }
        lock.lock()
        entries[Self.key(url)] = Entry(size: size, metadata: metadata)
        lock.unlock()
    }

    func forget(_ url: URL) {
        lock.lock()
        entries[Self.key(url)] = nil
        lock.unlock()
    }

    private static func key(_ url: URL) -> String {
        url.standardizedFileURL.path
    }

    /// Read from the file system each time: a URL keeps the resource values it read once.
    private static func size(of url: URL) -> Int? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return nil }
        return (attributes[.size] as? NSNumber)?.intValue
    }
}

/// An installed full foreign timetable: its source and its file.
private struct ForeignFile: Sendable {
    let sourceID: String
    let url: URL
}

/// The board of one file before the files are merged; `border`: no national pack of the file
/// serves the station (`TimetableCopies.rank`).
private struct BoardSelection {
    let ordered: [StationBoardCandidate]
    let firstNextDay: StationBoardCandidate?
    let someEnded: Bool
    let border: Bool
}

/// A train of a service day read for «Partenze», with its GTFS second, its feed, and what tells a
/// copy another pack publishes of it (`TimetableCopies.Run`).
private struct StationBoardCandidate {
    let entry: StationBoardDeparture
    let seconds: Int64
    let attribution: String
    let run: TimetableCopies.Run
}

/// The last stop of a trip: its terminus on the board.
private struct TripLastStop {
    let stopID: Int64
    let sequence: Int64
    let name: String
    let externalID: String
    let catalogStationID: String?
    let railwayCode: String?
}

/// One bundled GTFS pack as listed in the artifact's `pack` table.
public struct GTFSSourcePack: Equatable, Hashable, Sendable {
    public let sourceID: String
    public let attribution: String
    /// The last calendar day on which the pack's services run.
    public let lastServiceDate: Date
    public let tripCount: Int

    public init(sourceID: String, attribution: String, lastServiceDate: Date, tripCount: Int) {
        self.sourceID = sourceID
        self.attribution = attribution
        self.lastServiceDate = lastServiceDate
        self.tripCount = tripCount
    }
}

// MARK: - Trip Updates

extension SQLiteGTFSJourneyRepository: TimetableTripReading {
    /// At most this many trips are read for one question: a board page and a search list far fewer.
    static let maximumRealtimeTrips = 128
    private static let maximumTripStops = 1_000
    private static let maximumPackTimeZones = 16

    /// The stops of `trips` for their Trip Updates (`TimetableRealtime`), each read from the file that answers for
    /// its pack: an installed foreign timetable, else the base index. A file that cannot be read gives no trip.
    public func tripStops(_ trips: [TimetableTripKey]) -> [TimetableTripKey: TimetableTripStops] {
        var byFile: [URL: [TimetableTripKey]] = [:]
        var seen = Set<TimetableTripKey>()
        for trip in trips where seen.count < Self.maximumRealtimeTrips && seen.insert(trip).inserted {
            byFile[foreignDatabaseURLs[trip.sourceID] ?? databaseURL, default: []].append(trip)
        }
        var result: [TimetableTripKey: TimetableTripStops] = [:]
        for (url, keys) in byFile {
            guard let found = try? Self.tripStops(keys, in: url) else { continue }
            result.merge(found) { first, _ in first }
        }
        return result
    }

    private static func tripStops(_ trips: [TimetableTripKey], in databaseURL: URL) throws -> [TimetableTripKey: TimetableTripStops] {
        let database = try SQLiteDatabase(readOnlyURL: databaseURL)
        _ = try validatedOnce(database, at: databaseURL)
        var zonesByPack: [String: [String]] = [:]
        var result: [TimetableTripKey: TimetableTripStops] = [:]
        for trip in trips {
            if zonesByPack[trip.sourceID] == nil {
                let statement = try database.statement(
                    "SELECT DISTINCT a.timezone FROM agency AS a JOIN pack AS p ON p.id = a.pack_id WHERE p.source_id = ? LIMIT ?"
                )
                try statement.bind(trip.sourceID, at: 1)
                try statement.bind(Int64(maximumPackTimeZones + 1), at: 2)
                var zones: [String] = []
                while try statement.step() {
                    guard zones.count < maximumPackTimeZones else { throw SQLiteGTFSFailure.limitExceeded }
                    if let zone = statement.text(at: 0) { zones.append(zone) }
                }
                zonesByPack[trip.sourceID] = zones
            }
            let statement = try database.statement(
                """
                SELECT s.external_id, st.stop_sequence, COALESCE(st.departure_seconds, st.arrival_seconds, -1),
                       s.catalog_station_id
                FROM trip AS t
                JOIN pack AS p ON p.id = t.pack_id
                JOIN stop_time AS st ON st.trip_id = t.id
                JOIN stop AS s ON s.id = st.stop_id
                WHERE p.source_id = ? AND t.external_id = ?
                ORDER BY st.stop_sequence
                LIMIT ?
                """
            )
            try statement.bind(trip.sourceID, at: 1)
            try statement.bind(trip.tripID, at: 2)
            try statement.bind(Int64(maximumTripStops + 1), at: 3)
            var stops: [TimetableTripStops.Stop] = []
            while try statement.step() {
                guard stops.count < maximumTripStops else { throw SQLiteGTFSFailure.limitExceeded }
                guard let stopID = statement.text(at: 0)?.nonEmpty else { throw SQLiteGTFSFailure.invalidData }
                let seconds = statement.int(at: 2)
                stops.append(TimetableTripStops.Stop(
                    stopID: stopID,
                    sequence: Int(statement.int(at: 1)),
                    seconds: seconds >= 0 && seconds <= Int64(maximumGTFSSeconds) ? Int(seconds) : nil,
                    catalogStationID: statement.text(at: 3)?.nonEmpty
                ))
            }
            guard !stops.isEmpty else { continue }
            result[trip] = TimetableTripStops(stops: stops, agencyTimeZones: zonesByPack[trip.sourceID] ?? [])
        }
        return result
    }
}

private enum SQLiteGTFSFailure: Error {
    case invalidDatabase, invalidSchema, invalidData, limitExceeded, noRoute, scheduleExpired
}

private final class SQLiteDatabase {
    private var handle: OpaquePointer?
    /// The packs another file answers for (`foreignDatabaseURLs`): the queries of the planner and
    /// of «Partenze» skip them. Empty for every file but the base index with foreign files installed.
    var excludedSourceIDs: [String] = []

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

    /// « AND <column> NOT IN (?, …)» for `excludedSourceIDs`, or nothing: without foreign files
    /// every query is the one it always was.
    func excludedPacksClause(_ column: String) -> String {
        guard !excludedSourceIDs.isEmpty else { return "" }
        let placeholders = Array(repeating: "?", count: excludedSourceIDs.count).joined(separator: ",")
        return "\n              AND \(column) NOT IN (\(placeholders))"
    }

    /// Binds `excludedSourceIDs` from `binding` on, in the order of `excludedPacksClause`.
    func bindExcludedPacks(_ statement: SQLiteStatement, from binding: inout Int32) throws {
        for sourceID in excludedSourceIDs {
            try statement.bind(sourceID, at: binding)
            binding += 1
        }
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
