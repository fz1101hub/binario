import Foundation

extension SQLiteGTFSJourneyRepository {
    /// Journeys with changes computed on the iPhone from the bundled timetables, as Android's
    /// `EmbeddedGtfsJourneyProvider` computes them (Connection Scan, `ConnectionScanPlanner`): offline, open data,
    /// scheduled times only. The base index and every installed full foreign timetable take part; a pack another file
    /// answers for is read from that file only. Heavy: call it off the main actor.
    public func planJourneys(_ request: JourneyPlanRequest, isCancelled: @escaping @Sendable () -> Bool = { false }) -> JourneyPlanResult {
        let foreign = foreignFiles
        var files: [(url: URL, excluded: Set<String>)] = [(databaseURL, Set(foreign.map(\.sourceID)))]
        files += foreign.map { ($0.url, Set<String>()) }
        return JourneyGraphSearch(request: request, files: files, isCancelled: isCancelled).run()
    }
}

/// One search of `planJourneys`.
final class JourneyGraphSearch {
    static let minutesPerDay = 24 * 60
    /// Service days read per pack in one search: the previous, the requested and the next one; at most 63 slices.
    static let maxSlices = 63
    static let maxDayParts = 3

    private let request: JourneyPlanRequest
    private let files: [(url: URL, excluded: Set<String>)]
    private let isCancelled: @Sendable () -> Bool
    private let settings = JourneyPlannerSettings()
    private let planner: JourneyPlannerSettings
    private let filter: SearchFilter
    private let calendar: Calendar
    private var databases: [String: SQLiteDatabase] = [:]
    private var packs: [PlannerPack] = []
    private var unreadableFile = false

    init(request: JourneyPlanRequest, files: [(url: URL, excluded: Set<String>)], isCancelled: @escaping @Sendable () -> Bool) {
        self.request = request
        self.files = files
        self.isCancelled = isCancelled
        planner = settings.with(request.preferences)
        filter = SearchFilter(request.preferences)
        calendar = SQLiteGTFSJourneyRepository.serviceCalendar(timeZone: TimeZone(identifier: "Europe/Rome") ?? .current)
    }

    func run() -> JourneyPlanResult {
        do {
            return try search()
        } catch {
            return JourneyPlanResult(status: .unavailable, options: [])
        }
    }

    // MARK: - Search

    private func search() throws -> JourneyPlanResult {
        try openFiles()
        guard var origin = endpoint(request.origin), var destination = endpoint(request.destination) else {
            return JourneyPlanResult(status: .notCovered, options: [])
        }
        locateStop(&origin)
        locateStop(&destination)
        let serviceDay = calendar.startOfDay(for: request.departure)
        let serviceDate = SQLiteGTFSJourneyRepository.encodedDate(serviceDay, calendar: calendar)
        let reference = try self.reference(serviceDay)
        let startMinute = max(Int((request.departure.timeIntervalSince(reference) / 60).rounded(.down)), 0)
        let endMinute = startMinute + settings.searchWindowMinutes

        let (coverage, unreadable) = try packCoverage(origin, destination)
        var incomplete = unreadable || unreadableFile
        guard coverage.contains(where: \.coversOrigin), coverage.contains(where: \.coversDestination) else {
            return JourneyPlanResult(status: .notCovered, options: [], incompleteCoverage: incomplete)
        }
        let expired = expiredPacks(coverage, origin, destination, serviceDate)
        let expiredSources = Dictionary(expired.map { ($0.pack.sourceID, $0.pack.lastServiceDate) }, uniquingKeysWith: { first, _ in first })
        let current = coverage.filter { $0.pack.lastServiceDate >= serviceDate }
        guard current.contains(where: \.coversOrigin), current.contains(where: \.coversDestination) else {
            return JourneyPlanResult(status: .scheduleExpired, options: [], incompleteCoverage: incomplete, staleTimetables: true,
                                     expiredSources: expiredSources)
        }
        // A pack that ended before the searched day is left out: the answer says so even when the others find journeys.
        let stale = !expired.isEmpty

        // The planned order of the packs: when two packs publish the same train the copy of the first is kept, and the
        // first take the bounded places below.
        let ranked = JourneyPlannerRules.plannedOrder(
            current.filter { !$0.pack.isUrban }, sourceID: { $0.pack.sourceID },
            coversOrigin: { $0.coversOrigin }, coversDestination: { $0.coversDestination }
        )
        // The city networks of the two ends, next to the railway packs of every selection.
        let urban = Array(current.filter(\.pack.isUrban).enumerated().sorted { a, b in
            let first = (a.element.coversOrigin ? 1 : 0) + (a.element.coversDestination ? 1 : 0)
            let second = (b.element.coversOrigin ? 1 : 0) + (b.element.coversDestination ? 1 : 0)
            return first != second ? first > second : a.offset < b.offset
        }.map(\.element).prefix(settings.maxUrbanPacksPerSearch))
        // Every pack serving both ends takes part; maxPacksPerSearch only bounds the packs added for a change between packs.
        let primary = Array(ranked.filter { $0.coversOrigin && $0.coversDestination }.prefix(max(Self.maxSlices / Self.maxDayParts - urban.count, 0)))
        var railSelections: [[PackCoverage]]
        if ranked.contains(where: \.coversOrigin), ranked.contains(where: \.coversDestination) {
            let union = Array(unionForChanges(ranked, origin, destination).prefix(settings.maxPacksPerSearch))
            // The free places of the first selection go to the packs the change pairing chose.
            let primaryWithChanges = Array(Self.distinct(primary + union).prefix(max(primary.count, settings.maxPacksPerSearch)))
            railSelections = [primaryWithChanges, union]
        } else {
            // One end is reached only by a city network: the railway packs of the other end.
            railSelections = [Array(ranked.filter { $0.coversOrigin || $0.coversDestination }.prefix(settings.maxPacksPerSearch))]
        }
        railSelections = railSelections.filter { !$0.isEmpty }.map { selection in
            selection.sorted { a, b in
                (ranked.firstIndex { $0 === a } ?? Int.max) < (ranked.firstIndex { $0 === b } ?? Int.max)
            }
        }
        // A journey to or from a city stop takes the city lines from the start; between two railway stations the
        // railway packs are searched first and the city lines join only when they find nothing.
        let endAtCityStop = urban.contains { coverage in
            coverage.pack.sourceID == origin.gtfsStop?.sourceID || coverage.pack.sourceID == destination.gtfsStop?.sourceID
        }
        let endAtRailStop = !urban.isEmpty && [origin, destination].contains { end in
            guard let source = end.gtfsStop?.sourceID else { return false }
            return packs.contains { !$0.isUrban && $0.sourceID == source }
        }
        var selections: [[PackCoverage]] = []
        if endAtRailStop, !endAtCityStop { selections += railSelections.map { $0 + urban } }
        if !endAtCityStop { selections += railSelections }
        if urban.contains(where: \.coversOrigin), urban.contains(where: \.coversDestination) { selections.append(primary + urban) }
        if !urban.isEmpty { selections += railSelections.map { $0 + urban } }
        var seen = Set<[String]>()
        selections = selections.filter { !$0.isEmpty && seen.insert($0.map(\.pack.cacheKey)).inserted }

        var options: [JourneyPlanOption] = []
        for selection in selections {
            if isCancelled() { break }
            let outcome = try searchPacks(selection, origin: origin, destination: destination, serviceDay: serviceDay,
                                          serviceDate: serviceDate, startMinute: startMinute, endMinute: endMinute)
            incomplete = incomplete || outcome.incomplete
            options = outcome.options
            if !options.isEmpty { break }
        }
        if options.isEmpty {
            // As Android's JourneySearchService: an ended timetable without journeys is an expired one.
            let status: JourneyPlanStatus = stale ? .scheduleExpired : .noSolution
            let noData = status == .noSolution && filter.wheelchairOnly && !hasWheelchairData(coverage, origin, destination)
            return JourneyPlanResult(status: status, options: [], incompleteCoverage: false, accessibilityDataUnavailable: noData,
                                     staleTimetables: stale, expiredSources: expiredSources)
        }
        return JourneyPlanResult(status: .found, options: options, incompleteCoverage: incomplete, staleTimetables: stale,
                                 expiredSources: stale ? expiredSources : [:])
    }

    private func openFiles() throws {
        for (index, file) in files.enumerated() {
            do {
                let database = try SQLiteDatabase(readOnlyURL: file.url)
                _ = try SQLiteGTFSJourneyRepository.validatedOnce(database, at: file.url)
                let found = try SQLiteGTFSJourneyRepository.plannerPacks(in: database, url: file.url, excluded: file.excluded)
                guard let key = found.first?.fileKey else { continue }
                databases[key] = database
                packs += found
            } catch {
                // The base index must answer; a foreign file that cannot is left out, and the answer says so.
                if index == 0 { throw error }
                unreadableFile = true
            }
        }
    }

    private func database(_ pack: PlannerPack) throws -> SQLiteDatabase {
        guard let database = databases[pack.fileKey] else { throw SQLiteGTFSFailure.invalidDatabase }
        return database
    }

    private func stops(_ pack: PlannerPack) throws -> PlannerPackStops {
        try SQLiteGTFSJourneyRepository.plannerStops(pack, database: database(pack))
    }

    /// The GTFS reference of a service day: noon minus twelve hours, the instant its times count from.
    private func reference(_ serviceDay: Date) throws -> Date {
        try SQLiteGTFSJourneyRepository.gtfsDate(serviceDay: serviceDay, seconds: 0, calendar: calendar)
    }

    // MARK: - Ends

    struct Endpoint {
        let name: String
        let catalogueID: String?
        let gtfsStop: (sourceID: String, stopID: String)?
        var point: (latitude: Double, longitude: Double)?
    }

    /// A catalogue station, or a city stop picked in the search (`gtfs:<pack>:<stop>`): that stop only, never a
    /// railway station by name.
    private func endpoint(_ identity: StationIdentity) -> Endpoint? {
        if let stop = UrbanStops.parse(identity.id) {
            return Endpoint(name: identity.name, catalogueID: nil, gtfsStop: stop, point: nil)
        }
        let station = request.stations[identity.id]
        let point = station.flatMap { station -> (latitude: Double, longitude: Double)? in
            guard let latitude = station.latitude, let longitude = station.longitude else { return nil }
            return (latitude: latitude, longitude: longitude)
        }
        return Endpoint(name: station?.name ?? identity.name, catalogueID: identity.id, gtfsStop: nil, point: point)
    }

    /// A stop picked from a pack without a catalogue position takes its pack's coordinates.
    private func locateStop(_ endpoint: inout Endpoint) {
        guard endpoint.point == nil, let stop = endpoint.gtfsStop,
              let pack = packs.first(where: { $0.sourceID == stop.sourceID }),
              let stops = try? stops(pack), let index = stops.indexByExternalID[stop.stopID]
        else { return }
        endpoint.point = stops.stopPoint(index, stations: request.stations)
    }

    /// The transfer points of `stops` for `endpoint` (Android's `nodesFor`): its catalogue station's, or the stop
    /// picked.
    private func nodes(_ endpoint: Endpoint, in stops: PlannerPackStops) -> [String] {
        var nodes: [String] = []
        if let id = endpoint.catalogueID, stops.stopsByNode[id] != nil { nodes.append(id) }
        if let stop = endpoint.gtfsStop, stop.sourceID == stops.sourceID, let index = stops.indexByExternalID[stop.stopID] {
            let node = stops.nodeKeys[index]
            if !nodes.contains(node) { nodes.append(node) }
        }
        return nodes
    }

    /// The transfer points a journey can start or end at for `endpoint` (Android's `accessFor`): 0 for its own nodes,
    /// the walk for the other points within the longest walk of its coordinates.
    private func access(_ endpoint: Endpoint, in stops: PlannerPackStops, settings: JourneyPlannerSettings) -> NodeMinutes {
        var result = NodeMinutes()
        for node in nodes(endpoint, in: stops) { result.set(node, 0) }
        guard let point = endpoint.point, settings.maxWalkMeters > 0 else { return result }
        let maximum = Double(settings.maxWalkMeters)
        for index in 0..<stops.count where !stops.latitudes[index].isNaN && !stops.longitudes[index].isNaN {
            let node = stops.nodeKeys[index]
            if result.minutes(node) == 0 { continue }
            let target = request.stations[node].flatMap { station -> (latitude: Double, longitude: Double)? in
                guard let latitude = station.latitude, let longitude = station.longitude else { return nil }
                return (latitude: latitude, longitude: longitude)
            } ?? (latitude: stops.latitudes[index], longitude: stops.longitudes[index])
            let meters = JourneyPlannerRules.distanceMeters(point, target)
            if meters > maximum { continue }
            let minutes = settings.walkMinutes(meters)
            if let known = result.minutes(node), known <= minutes { continue }
            result.set(node, minutes)
        }
        return result
    }

    /// Transfer points with their minutes of walking, in the order they were found.
    struct NodeMinutes {
        private(set) var keys: [String] = []
        private var values: [String: Int] = [:]

        var isEmpty: Bool { keys.isEmpty }
        func minutes(_ node: String) -> Int? { values[node] }
        var containsExact: Bool { values.values.contains(0) }

        mutating func set(_ node: String, _ minutes: Int) {
            if values[node] == nil { keys.append(node) }
            values[node] = minutes
        }
    }

    // MARK: - Packs

    final class PackCoverage {
        let pack: PlannerPack
        let stops: PlannerPackStops
        let originNodes: NodeMinutes
        let destinationNodes: NodeMinutes

        init(pack: PlannerPack, stops: PlannerPackStops, originNodes: NodeMinutes, destinationNodes: NodeMinutes) {
            self.pack = pack
            self.stops = stops
            self.originNodes = originNodes
            self.destinationNodes = destinationNodes
        }

        var coversOrigin: Bool { !originNodes.isEmpty }
        var coversDestination: Bool { !destinationNodes.isEmpty }
    }

    /// A foreign pack read as its border subset does not cover an end its full pack serves outside the zone.
    private func coversEnd(_ pack: PlannerPack, _ stationID: String?) -> Bool {
        request.borderZones[pack.sourceID]?.contains(stationID) ?? true
    }

    /// A city pack joins a search with an end at one of its stops or a short walk from one (Android's area check
    /// before `accessFor`: a city whose stops no end can walk to adds nothing).
    private func urbanCandidate(_ pack: PlannerPack, _ origin: Endpoint, _ destination: Endpoint) -> Bool {
        guard settings.maxUrbanPacksPerSearch > 0 else { return false }
        if origin.gtfsStop?.sourceID == pack.sourceID || destination.gtfsStop?.sourceID == pack.sourceID { return true }
        return origin.point != nil || destination.point != nil
    }

    /// The packs that know `origin` or `destination` (their own stops, or stops a short walk away), and whether a pack
    /// over its reading limits had to be left out.
    private func packCoverage(_ origin: Endpoint, _ destination: Endpoint) throws -> ([PackCoverage], Bool) {
        var unreadable = false
        var coverage: [PackCoverage] = []
        for pack in packs {
            if isCancelled() { break }
            if pack.isUrban, !urbanCandidate(pack, origin, destination) { continue }
            let stops: PlannerPackStops
            do {
                stops = try self.stops(pack)
            } catch SQLiteGTFSFailure.limitExceeded {
                unreadable = true
                continue
            }
            let originNodes = coversEnd(pack, origin.catalogueID) ? access(origin, in: stops, settings: planner) : NodeMinutes()
            let destinationNodes = coversEnd(pack, destination.catalogueID) ? access(destination, in: stops, settings: planner) : NodeMinutes()
            if !originNodes.isEmpty || !destinationNodes.isEmpty {
                coverage.append(PackCoverage(pack: pack, stops: stops, originNodes: originNodes, destinationNodes: destinationNodes))
            }
        }
        return (coverage, unreadable)
    }

    /// The packs that end before `serviceDate` and whose trains a journey of that day would miss: a railway pack with a
    /// stop of its own at an end, a city network when an end is one of its stops.
    private func expiredPacks(_ coverage: [PackCoverage], _ origin: Endpoint, _ destination: Endpoint, _ serviceDate: Int) -> [PackCoverage] {
        coverage.filter { covered in
            guard covered.pack.lastServiceDate < serviceDate else { return false }
            if covered.pack.isUrban {
                let id = covered.pack.sourceID
                return origin.gtfsStop?.sourceID == id || destination.gtfsStop?.sourceID == id
            }
            return covered.originNodes.containsExact || covered.destinationNodes.containsExact
        }
    }

    /// True when the packs serving both ends publish wheelchair data (accessible or not) for at least one stop of
    /// each end: otherwise «Solo accessibili» can never find anything there.
    private func hasWheelchairData(_ coverage: [PackCoverage], _ origin: Endpoint, _ destination: Endpoint) -> Bool {
        let originKnown = coverage.contains { $0.coversOrigin && $0.stops.wheelchairKnown($0.originNodes.keys) }
        let destinationKnown = coverage.contains { $0.coversDestination && $0.stops.wheelchairKnown($0.destinationNodes.keys) }
        return originKnown && destinationKnown
    }

    /// Packs for a journey that changes between packs (Android's `unionForChanges`): a pack serving the origin and one
    /// serving the destination pair up when they share a catalogue station, other than the ends, that is no large detour
    /// (at most 1.5 times the direct distance plus 30 km), by increasing detour while both fit; otherwise the best
    /// ranked packs of either end.
    private func unionForChanges(_ ranked: [PackCoverage], _ origin: Endpoint, _ destination: Endpoint) -> [PackCoverage] {
        func point(_ id: String?) -> (latitude: Double, longitude: Double)? {
            guard let id, let station = request.stations[id], let latitude = station.latitude, let longitude = station.longitude else { return nil }
            return (latitude: latitude, longitude: longitude)
        }
        var chosen = Set<String>()
        if let originPoint = point(origin.catalogueID), let destinationPoint = point(destination.catalogueID) {
            let direct = JourneyPlannerRules.distanceMeters(originPoint, destinationPoint) / 1_000
            var pairs: [(first: PackCoverage, second: PackCoverage, detour: Double)] = []
            for first in ranked where first.coversOrigin {
                for second in ranked where second.coversDestination {
                    if first === second { continue }
                    var best: Double?
                    for id in first.stops.catalogueStations
                    where second.stops.catalogueStations.contains(id) && id != origin.catalogueID && id != destination.catalogueID {
                        guard let shared = point(id) else { continue }
                        let detour = (JourneyPlannerRules.distanceMeters(originPoint, shared) + JourneyPlannerRules.distanceMeters(shared, destinationPoint)) / 1_000
                        guard detour <= direct * 1.5 + 30 else { continue }
                        best = min(best ?? detour, detour)
                    }
                    if let best { pairs.append((first, second, best)) }
                }
            }
            let sorted = pairs.enumerated().sorted { a, b in
                a.element.detour != b.element.detour ? a.element.detour < b.element.detour : a.offset < b.offset
            }.map(\.element)
            for pair in sorted {
                let added = Set([pair.first.pack.cacheKey, pair.second.pack.cacheKey]).subtracting(chosen)
                if chosen.count + added.count <= settings.maxPacksPerSearch { chosen.formUnion(added) }
            }
        }
        if !chosen.isEmpty { return ranked.filter { chosen.contains($0.pack.cacheKey) } }
        return Self.distinct(Array(ranked.filter(\.coversOrigin).prefix(1)) + Array(ranked.filter(\.coversDestination).prefix(1)) + ranked)
    }

    private static func distinct(_ coverage: [PackCoverage]) -> [PackCoverage] {
        var seen = Set<String>()
        return coverage.filter { seen.insert($0.pack.cacheKey).inserted }
    }

    // MARK: - One selection of packs

    private func searchPacks(
        _ selection: [PackCoverage], origin: Endpoint, destination: Endpoint, serviceDay: Date, serviceDate: Int,
        startMinute: Int, endMinute: Int
    ) throws -> (options: [JourneyPlanOption], incomplete: Bool) {
        let lastConnectionMinute = endMinute + settings.journeyHorizonMinutes
        let window = try scanWindow(selection, serviceDay: serviceDay, startMinute: startMinute, lastConnectionMinute: lastConnectionMinute)
        var incomplete = window.incomplete
        let query = try window.query(request, filter: filter, departureMinute: startMinute, latestDepartureMinute: endMinute,
                                     lastConnectionMinute: lastConnectionMinute, settings: settings)
        let scanner = ConnectionScanPlanner(timetable: window.graph.timetable)
        let outcome = scanner.planWithOutcome(query, isCancelled: isCancelled)
        var journeys = outcome.journeys
        var found = window
        var foundScanner = scanner
        // Nothing leaves in the window: the first option after it follows («primo domani alle …», «Nessun treno fino
        // alle …»), first on the connections already read, then on a window of its own up to the next day's noon.
        let emptyWindow = outcome.exhausted && journeys.isEmpty
        if emptyWindow {
            if lastConnectionMinute > endMinute {
                var followUp = query
                followUp.departureMinute = endMinute + 1
                followUp.latestDepartureMinute = lastConnectionMinute
                followUp.maxResults = 1
                followUp.maxIterations = SharedData.JourneyPlanner.followUpIterations
                journeys = scanner.plan(followUp, isCancelled: isCancelled)
            }
            let nextNoon = Self.minutesPerDay + SharedData.JourneyPlanner.nextDayFollowUpMinutes
            if journeys.isEmpty, lastConnectionMinute < nextNoon {
                let from = lastConnectionMinute + 1
                let horizon = nextNoon + settings.journeyHorizonMinutes
                let later = try scanWindow(selection, serviceDay: serviceDay, startMinute: from, lastConnectionMinute: horizon)
                incomplete = incomplete || later.incomplete
                let laterScanner = ConnectionScanPlanner(timetable: later.graph.timetable)
                var followUp = try later.query(request, filter: filter, departureMinute: from, latestDepartureMinute: nextNoon,
                                               lastConnectionMinute: horizon, settings: settings)
                followUp.maxResults = 1
                followUp.maxIterations = SharedData.JourneyPlanner.followUpIterations
                journeys = laterScanner.plan(followUp, isCancelled: isCancelled)
                found = later
                foundScanner = laterScanner
            }
        }
        let slices = found.slices
        let graph = found.graph
        // «Ultimo di oggi»: the latest option of the requested service day is its last when the list holds an option of
        // a later day, or when every option of the window was found and the window ends after the last departure of
        // that day in these packs. An option that waits for hours at a change is never announced as the last.
        func serviceDayOf(_ journey: ScannedJourney) -> Int { slices[graph.sliceOf[journey.legs[0].boardConnection]].day.serviceDate }
        let dayEnd = window.slices.filter { $0.offset == 0 }.map(\.day.lastDepartureMinute).max() ?? Int.max
        let dayComplete = journeys.contains { serviceDayOf($0) > serviceDate } || (outcome.exhausted && endMinute >= dayEnd)
        var lastOfDay: Int?
        if dayComplete {
            var best: (index: Int, departure: Int)?
            for (index, journey) in journeys.enumerated() where serviceDayOf(journey) <= serviceDate {
                if best == nil || journey.departureMinute > best!.departure { best = (index, journey.departureMinute) }
            }
            if let best, foundScanner.excessWait(journeys[best.index], settings.maxChangeWaitMinutes) == 0 { lastOfDay = best.index }
        }
        let reference = try self.reference(serviceDay)
        let options = journeys.enumerated().map { index, journey -> JourneyPlanOption in
            var option = toOption(journey, graph: graph, slices: slices, origin: origin, destination: destination,
                                  reference: reference, originAccessible: found.originAccessible,
                                  destinationAccessible: found.destinationAccessible)
            option.lastOfServiceDay = index == lastOfDay
            option.firstAfterEmptyWindow = emptyWindow
            return option
        }
        return (options, incomplete)
    }

    struct Slice {
        let coverage: PackCoverage
        let day: PlannerDay
        /// Minutes from the requested service day's reference to this day's.
        let offset: Int
    }

    /// The graph of one scan window with its ends marked.
    struct ScanWindow {
        let slices: [Slice]
        let graph: SearchGraph
        let incomplete: Bool
        let origins: [Bool]
        let destinations: [Bool]
        let originWalk: [Int]
        let destinationWalk: [Int]
        let originAccessible: Bool
        let destinationAccessible: Bool

        func query(_ request: JourneyPlanRequest, filter: SearchFilter, departureMinute: Int, latestDepartureMinute: Int,
                   lastConnectionMinute: Int, settings: JourneyPlannerSettings) throws -> ConnectionScanQuery {
            try ConnectionScanQuery(
                origins: origins, destinations: destinations, departureMinute: departureMinute,
                latestDepartureMinute: latestDepartureMinute, maxTransfers: request.maxTransfers, maxResults: request.maxResults,
                maxIterations: settings.maxIterations, lastConnectionMinute: lastConnectionMinute, originWalkMinutes: originWalk,
                destinationWalkMinutes: destinationWalk, preferLessWalking: filter.preferLessWalking,
                maxChangeWaitMinutes: settings.maxChangeWaitMinutes
            )
        }
    }

    /// The connections of `selection` leaving from `startMinute` whose vehicles can be boarded up to
    /// `lastConnectionMinute`, with the origin and destination points marked: the previous service day past its
    /// midnight, the requested one, and the next one when the horizon reaches it.
    private func scanWindow(_ selection: [PackCoverage], serviceDay: Date, startMinute: Int, lastConnectionMinute: Int) throws -> ScanWindow {
        var incomplete = false
        let reference = try self.reference(serviceDay)
        var parts: [Date] = []
        for offset in [-1, 0, 1] where offset < 1 || lastConnectionMinute >= Self.minutesPerDay {
            guard let day = calendar.date(byAdding: .day, value: offset, to: serviceDay) else { throw SQLiteGTFSFailure.invalidData }
            parts.append(day)
        }
        var slices: [Slice] = []
        for coverage in selection {
            let database = try database(coverage.pack)
            let timetable: PlannerPackTimetable
            do {
                timetable = try SQLiteGTFSJourneyRepository.plannerTimetable(coverage.pack, stops: coverage.stops, database: database)
            } catch SQLiteGTFSFailure.limitExceeded {
                incomplete = true
                continue
            }
            for part in parts {
                let date = SQLiteGTFSJourneyRepository.encodedDate(part, calendar: calendar)
                let day = try SQLiteGTFSJourneyRepository.plannerDay(coverage.pack, timetable: timetable, serviceDate: date, calendar: calendar)
                guard day.connectionCount > 0 else { continue }
                let partReference = try self.reference(part)
                let offset = Int((partReference.timeIntervalSince(reference) / 60).rounded())
                slices.append(Slice(coverage: coverage, day: day, offset: offset))
            }
        }
        if slices.count > Self.maxSlices {
            slices = Array(slices.prefix(Self.maxSlices))
            incomplete = true
        }
        let graph = try buildGraph(slices, startMinute: startMinute, lastMinute: lastConnectionMinute)
        let nodeCount = graph.timetable.nodeCount
        var origins = Array(repeating: false, count: nodeCount)
        var destinations = Array(repeating: false, count: nodeCount)
        var originWalk = Array(repeating: -1, count: nodeCount)
        var destinationWalk = Array(repeating: -1, count: nodeCount)
        // A walk from or to a requested station is wheelchair accessible only when both ends are published so.
        let originAccessible = selection.contains { coverage in
            coverage.originNodes.keys.contains { coverage.originNodes.minutes($0) == 0 && coverage.stops.nodeHasAccessibleStop($0) }
        }
        let destinationAccessible = selection.contains { coverage in
            coverage.destinationNodes.keys.contains { coverage.destinationNodes.minutes($0) == 0 && coverage.stops.nodeHasAccessibleStop($0) }
        }
        func mark(_ nodes: NodeMinutes, exact: inout [Bool], walk: inout [Int], endAccessible: Bool) {
            for key in nodes.keys {
                guard let node = graph.nodeIndex[key], let minutes = nodes.minutes(key) else { continue }
                if minutes == 0 {
                    exact[node] = true
                } else if filter.wheelchairOnly, !endAccessible || graph.nodeAccessible?[node] != true {
                    continue
                } else if walk[node] < 0 || minutes < walk[node] {
                    walk[node] = minutes
                }
            }
        }
        for coverage in selection {
            mark(coverage.originNodes, exact: &origins, walk: &originWalk, endAccessible: originAccessible)
            mark(coverage.destinationNodes, exact: &destinations, walk: &destinationWalk, endAccessible: destinationAccessible)
        }
        return ScanWindow(
            slices: slices, graph: graph, incomplete: incomplete || graph.truncated, origins: origins, destinations: destinations,
            originWalk: originWalk, destinationWalk: destinationWalk, originAccessible: originAccessible,
            destinationAccessible: destinationAccessible
        )
    }

    // MARK: - Graph

    struct SearchGraph {
        let timetable: ConnectionScanTimetable
        let nodeKeys: [String]
        let nodeIndex: [String: Int]
        let sliceOf: [Int]
        let connectionOf: [Int]
        let truncated: Bool
        /// First global trip index of each slice.
        let tripOffsets: [Int]
        /// Per group of copies of a run, the trip that continues it with the same vehicle, or -1.
        let successor: [Int]
        /// Per global trip, its group of copies.
        let runGroup: [Int]
        /// With «Solo accessibili», the transfer points with a stop published as accessible.
        let nodeAccessible: [Bool]?

        func sliceOfTrip(_ global: Int) -> Int {
            var index = tripOffsets.count - 1
            while index > 0, tripOffsets[index] > global { index -= 1 }
            return index
        }
    }

    struct Footpaths {
        let start: [Int]
        let target: [Int]
        let minutes: [Int]

        func joined(_ from: Int, _ to: Int) -> Bool {
            if from == to { return true }
            for index in start[from]..<start[from + 1] where target[index] == to { return true }
            return false
        }
    }

    private static let maxEncodedDuration = 0x3FFF

    private func buildGraph(_ slices: [Slice], startMinute: Int, lastMinute: Int) throws -> SearchGraph {
        var nodeIndex: [String: Int] = [:]
        var nodeKeys: [String] = []
        func node(_ key: String) -> Int {
            if let known = nodeIndex[key] { return known }
            nodeKeys.append(key)
            nodeIndex[key] = nodeKeys.count - 1
            return nodeKeys.count - 1
        }
        var stopNodes: [[Int]] = []
        for slice in slices {
            var nodes = Array(repeating: -1, count: slice.coverage.stops.count)
            for stop in slice.day.dayStops { nodes[stop] = node(slice.coverage.stops.nodeKeys[stop]) }
            stopNodes.append(nodes)
        }
        var tripOffsets: [Int] = []
        var tripCount = 0
        for slice in slices {
            tripOffsets.append(tripCount)
            tripCount += slice.day.trips.count
        }
        // Travel preferences: the trips of an avoided kind, and without a published wheelchair access the trips and
        // stops not marked accessible, take no part (unknown is never accessible).
        let tripAllowed: [[Bool]?] = slices.map { slice in
            filter.active ? slice.day.trips.map(filter.allows) : nil
        }
        let stopAccessible: [[Bool]]? = filter.wheelchairOnly
            ? slices.map { slice in (0..<slice.coverage.stops.count).map(slice.coverage.stops.stopAccessible) }
            : nil
        var nodeAccessible: [Bool]?
        if let stopAccessible {
            var accessible = Array(repeating: false, count: nodeKeys.count)
            for (sliceIndex, slice) in slices.enumerated() {
                for stop in slice.day.dayStops where stopAccessible[sliceIndex][stop] { accessible[stopNodes[sliceIndex][stop]] = true }
            }
            nodeAccessible = accessible
        }
        var keys: [Int] = []
        keys.reserveCapacity(4_096)
        var truncated = false
        func add(_ sliceIndex: Int, _ connection: Int, _ departure: Int) -> Bool {
            if keys.count == settings.maxSearchConnections {
                truncated = true
                return false
            }
            let day = slices[sliceIndex].day
            let duration = min(day.arrivalMinute[connection] - day.departureMinute[connection], Self.maxEncodedDuration)
            keys.append((departure << 42) | (duration << 28) | (sliceIndex << 22) | connection)
            return true
        }
        // Trips with a connection in the window: the only ones a continuation can join.
        var inWindow = Array(repeating: false, count: tripCount)
        window: for (sliceIndex, slice) in slices.enumerated() {
            let day = slice.day
            let allowed = tripAllowed[sliceIndex]
            let tripOffset = tripOffsets[sliceIndex]
            for connection in 0..<day.connectionCount {
                let departure = day.departureMinute[connection] + slice.offset
                if departure < startMinute || departure > lastMinute { continue }
                if let allowed, !allowed[day.connectionTrip[connection]] { continue }
                inWindow[tripOffset + day.connectionTrip[connection]] = true
                if !add(sliceIndex, connection, departure) { break window }
            }
        }
        // The rest of every trip boarded by then: a night train boarded inside the horizon is followed to its end.
        tail: for (sliceIndex, slice) in slices.enumerated() {
            if truncated { break }
            let day = slice.day
            let tripOffset = tripOffsets[sliceIndex]
            for local in day.trips.indices where inWindow[tripOffset + local] {
                let first = day.tripFirstConnection[local]
                for connection in first..<(first + day.tripConnectionCount[local]) {
                    let departure = day.departureMinute[connection] + slice.offset
                    if departure > lastMinute, !add(sliceIndex, connection, departure) { break tail }
                }
            }
        }
        let urbanPrefixes = Set(slices.filter { $0.coverage.pack.isUrban }.map { $0.coverage.pack.sourceID + ":" })
        let transferMinutes = nodeKeys.map { key -> Int in
            let railwayCode = request.stations[key]?.railwayCode
            if let railwayCode, settings.largeHubRailwayCodes.contains(railwayCode) { return settings.largeHubTransferMinutes }
            if urbanPrefixes.contains(where: { key.hasPrefix($0) }) { return settings.urbanTransferMinutes }
            return settings.railTransferMinutes(railwayCode)
        }
        let footpaths = self.footpaths(nodeKeys, slices: slices, transferMinutes: transferMinutes, accessible: nodeAccessible)
        let runs = mergeCrossPackRuns(slices, keys: &keys, stopNodes: stopNodes, tripOffsets: tripOffsets, tripCount: tripCount, inWindow: inWindow)
        let continuations = linkContinuations(slices, stopNodes: stopNodes, tripOffsets: tripOffsets, runs: runs, footpaths: footpaths,
                                              tripAllowed: tripAllowed)
        keys.sort()
        let count = keys.count
        var departureNode = [Int](repeating: 0, count: count)
        var arrivalNode = [Int](repeating: 0, count: count)
        var departureMinute = [Int](repeating: 0, count: count)
        var arrivalMinute = [Int](repeating: 0, count: count)
        var trip = [Int](repeating: 0, count: count)
        var boarding = [UInt8](repeating: 0, count: count)
        var sliceOf = [Int](repeating: 0, count: count)
        var connectionOf = [Int](repeating: 0, count: count)
        for (index, key) in keys.enumerated() {
            let sliceIndex = (key >> 22) & 0x3F
            let connection = key & 0x3FFFFF
            let slice = slices[sliceIndex]
            let day = slice.day
            departureNode[index] = stopNodes[sliceIndex][day.departureStop[connection]]
            arrivalNode[index] = stopNodes[sliceIndex][day.arrivalStop[connection]]
            departureMinute[index] = day.departureMinute[connection] + slice.offset
            arrivalMinute[index] = day.arrivalMinute[connection] + slice.offset
            trip[index] = runs.representative[tripOffsets[sliceIndex] + day.connectionTrip[connection]]
            let accessible = stopAccessible?[sliceIndex]
            boarding[index] = ConnectionScanTimetable.boardingFlags(
                pickupAllowed: day.pickupAllowed(connection) && (accessible?[day.departureStop[connection]] ?? true),
                dropOffAllowed: day.dropOffAllowed(connection) && (accessible?[day.arrivalStop[connection]] ?? true)
            )
            sliceOf[index] = sliceIndex
            connectionOf[index] = connection
        }
        let inSeat = inSeatContinuations(continuations.links, representative: runs.representative, trip: trip,
                                         arrivalNode: arrivalNode, arrivalMinute: arrivalMinute)
        let timetable = try ConnectionScanTimetable(
            nodeCount: nodeKeys.count, tripCount: tripCount, transferMinutes: transferMinutes, departureNode: departureNode,
            arrivalNode: arrivalNode, departureMinute: departureMinute, arrivalMinute: arrivalMinute, trip: trip, boarding: boarding,
            footStart: footpaths.start, footTarget: footpaths.target, footMinutes: footpaths.minutes,
            continuationStart: inSeat?.start, continuationTrip: inSeat?.trip ?? [], continuationNext: inSeat?.next ?? []
        )
        return SearchGraph(
            timetable: timetable, nodeKeys: nodeKeys, nodeIndex: nodeIndex, sliceOf: sliceOf, connectionOf: connectionOf,
            truncated: truncated, tripOffsets: tripOffsets, successor: continuations.successor, runGroup: continuations.group,
            nodeAccessible: nodeAccessible
        )
    }

    /// Walking transfers between the transfer points of one search at most the longest walk apart: the walk's minutes,
    /// never less than the change time of either end. Points are the catalogue station's, else the pack's; a node
    /// without coordinates has none. With `accessible`, walks only between points with an accessible stop.
    private func footpaths(_ nodeKeys: [String], slices: [Slice], transferMinutes: [Int], accessible: [Bool]?) -> Footpaths {
        let size = nodeKeys.count
        let maxMeters = Double(planner.maxWalkMeters)
        guard maxMeters > 0, size > 0 else { return Footpaths(start: Array(repeating: 0, count: size + 1), target: [], minutes: []) }
        var stopsInSearch: [PlannerPackStops] = []
        for slice in slices where !stopsInSearch.contains(where: { $0 === slice.coverage.stops }) { stopsInSearch.append(slice.coverage.stops) }
        var points: [(latitude: Double, longitude: Double)?] = []
        for key in nodeKeys {
            if let station = request.stations[key], let latitude = station.latitude, let longitude = station.longitude {
                points.append((latitude: latitude, longitude: longitude))
            } else {
                points.append(nil)
            }
        }
        for node in 0..<size where points[node] == nil {
            for stops in stopsInSearch {
                if let point = stops.point(nodeKeys[node], stations: [:]) {
                    points[node] = point
                    break
                }
            }
        }
        let cellDegrees = maxMeters / 111_000
        struct Cell: Hashable {
            let row: Int
            let column: Int
        }
        func cell(_ point: (latitude: Double, longitude: Double)) -> Cell {
            Cell(row: Int((point.latitude / cellDegrees).rounded(.down)),
                 column: Int((point.longitude * cos(point.latitude * .pi / 180) / cellDegrees).rounded(.down)))
        }
        var cells: [Cell: [Int]] = [:]
        for node in 0..<size {
            guard let point = points[node] else { continue }
            cells[cell(point), default: []].append(node)
        }
        var targets = Array(repeating: [Int](), count: size)
        var minutes = Array(repeating: [Int](), count: size)
        for node in 0..<size {
            guard let point = points[node] else { continue }
            if let accessible, !accessible[node] { continue }
            let home = cell(point)
            for deltaRow in -1...1 {
                for deltaColumn in -1...1 {
                    for other in cells[Cell(row: home.row + deltaRow, column: home.column + deltaColumn)] ?? [] {
                        if other == node { continue }
                        if let accessible, !accessible[other] { continue }
                        guard let otherPoint = points[other] else { continue }
                        let meters = JourneyPlannerRules.distanceMeters(point, otherPoint)
                        if meters > maxMeters { continue }
                        targets[node].append(other)
                        minutes[node].append(max(planner.walkMinutes(meters), transferMinutes[node], transferMinutes[other]))
                    }
                }
            }
        }
        var start: [Int] = []
        var target: [Int] = []
        var walk: [Int] = []
        for node in 0..<size {
            start.append(target.count)
            target += targets[node]
            walk += minutes[node]
        }
        start.append(target.count)
        return Footpaths(start: start, target: target, minutes: walk)
    }

    // MARK: - Copies and in-seat continuations

    /// `representative` maps a trip to the trip it is scanned as; with two packs or more `tripsBySharedNumber` lists
    /// the trips of every public number several packs publish.
    struct MergedRuns {
        var representative: [Int]
        var sliceOfTrip: [Int] = []
        var tripsBySharedNumber: [String: [Int]] = [:]
    }

    private struct RunConnection: Hashable {
        let number: String
        let from: Int
        let to: Int
        let departure: Int
        let arrival: Int
    }

    /// The same run published by two packs (a EuroCity in the Trenitalia and ÖBB packs) becomes one trip of the scan:
    /// a trip with the same public number as a trip of another pack and an identical connection takes its identity,
    /// and its identical connections are dropped (Android's `mergeCrossPackRuns`). `keys` are compacted in place.
    private func mergeCrossPackRuns(
        _ slices: [Slice], keys: inout [Int], stopNodes: [[Int]], tripOffsets: [Int], tripCount: Int, inWindow: [Bool]
    ) -> MergedRuns {
        var representative = Array(0..<tripCount)
        guard Set(slices.map(\.day.sourceID)).count >= 2 else { return MergedRuns(representative: representative) }
        var numberOfTrip = [String?](repeating: nil, count: tripCount)
        var sliceOfTrip = [Int](repeating: 0, count: tripCount)
        var sourcesByNumber: [String: Set<String>] = [:]
        for (sliceIndex, slice) in slices.enumerated() {
            for (local, dayTrip) in slice.day.trips.enumerated() {
                let global = tripOffsets[sliceIndex] + local
                sliceOfTrip[global] = sliceIndex
                // City runs have no public number and are never published twice.
                if dayTrip.mode.isUrban { continue }
                guard let number = JourneyPlannerRules.runNumberKey(dayTrip.publicNumber) else { continue }
                numberOfTrip[global] = number
                sourcesByNumber[number, default: []].insert(slice.day.sourceID)
            }
        }
        let shared = Set(sourcesByNumber.filter { $0.value.count > 1 }.keys)
        guard !shared.isEmpty else { return MergedRuns(representative: representative) }
        var sharedTrip = [Bool](repeating: false, count: tripCount)
        var tripsBySharedNumber: [String: [Int]] = [:]
        for trip in 0..<tripCount {
            guard let number = numberOfTrip[trip], shared.contains(number) else { continue }
            sharedTrip[trip] = true
            if inWindow[trip] { tripsBySharedNumber[number, default: []].append(trip) }
        }
        func root(_ trip: Int) -> Int {
            var current = trip
            while representative[current] != current { current = representative[current] }
            return current
        }
        var owner: [RunConnection: Int] = [:]
        var kept = 0
        for index in keys.indices {
            let key = keys[index]
            let sliceIndex = (key >> 22) & 0x3F
            let connection = key & 0x3FFFFF
            let slice = slices[sliceIndex]
            let day = slice.day
            let trip = tripOffsets[sliceIndex] + day.connectionTrip[connection]
            var duplicate = false
            if sharedTrip[trip], let number = numberOfTrip[trip] {
                let identity = RunConnection(
                    number: number,
                    from: stopNodes[sliceIndex][day.departureStop[connection]],
                    to: stopNodes[sliceIndex][day.arrivalStop[connection]],
                    departure: day.departureMinute[connection] + slice.offset,
                    arrival: day.arrivalMinute[connection] + slice.offset
                )
                if let first = owner[identity] {
                    if first != trip, slices[sliceOfTrip[first]].day.sourceID != day.sourceID {
                        let target = root(first)
                        if representative[trip] == trip, target != trip { representative[trip] = target }
                        duplicate = root(trip) == target
                    }
                } else {
                    owner[identity] = trip
                }
            }
            if !duplicate {
                keys[kept] = key
                kept += 1
            }
        }
        keys.removeLast(keys.count - kept)
        for trip in 0..<tripCount { representative[trip] = root(trip) }
        return MergedRuns(representative: representative, sliceOfTrip: sliceOfTrip, tripsBySharedNumber: tripsBySharedNumber)
    }

    /// The first and last connection of a trip, in the search's transfer points and minutes.
    private struct TripEnds {
        let firstNode: Int
        /// Where the first connection arrives: a continuation that turns straight back goes there again.
        let secondNode: Int
        let firstDeparture: Int
        /// Where the last connection leaves from.
        let previousNode: Int
        let lastNode: Int
        let lastArrival: Int
    }

    /// Trip `after` goes on from `junctionNode`, where trip `before` arrives at `junctionArrival`.
    struct InSeatLink {
        let before: Int
        let after: Int
        let junctionNode: Int
        let junctionArrival: Int
    }

    struct Continuations {
        let successor: [Int]
        let group: [Int]
        let links: [InSeatLink]
    }

    /// «Resta a bordo» (Android's `linkContinuations`): the trips the same vehicle runs one after the other. Trip B
    /// continues trip A when B leaves where A arrives (the same transfer point, or for two packs a point a footpath joins
    /// under the same name), no earlier than A arrives and at most the longest stay on board later, without turning back,
    /// and either both are in one pack and service day with the same block_id, or they are in two packs with the same
    /// public number of no conflicting kind. Every run continues at most one run and is continued by at most one.
    private func linkContinuations(
        _ slices: [Slice], stopNodes: [[Int]], tripOffsets: [Int], runs: MergedRuns, footpaths: Footpaths, tripAllowed: [[Bool]?]
    ) -> Continuations {
        let representative = runs.representative
        let tripCount = representative.count
        var successor = [Int](repeating: -1, count: tripCount)
        var candidates: [(wait: Int, before: Int, after: Int, junction: Int, arrival: Int)] = []
        func sliceOf(_ global: Int) -> Int {
            var index = tripOffsets.count - 1
            while index > 0, tripOffsets[index] > global { index -= 1 }
            return index
        }
        func ends(_ sliceIndex: Int, _ local: Int) -> TripEnds {
            let day = slices[sliceIndex].day
            let first = day.tripFirstConnection[local]
            let last = first + day.tripConnectionCount[local] - 1
            let offset = slices[sliceIndex].offset
            let nodes = stopNodes[sliceIndex]
            return TripEnds(
                firstNode: nodes[day.departureStop[first]], secondNode: nodes[day.arrivalStop[first]],
                firstDeparture: day.departureMinute[first] + offset, previousNode: nodes[day.departureStop[last]],
                lastNode: nodes[day.arrivalStop[last]], lastArrival: day.arrivalMinute[last] + offset
            )
        }
        func allowed(_ global: Int) -> Bool {
            let sliceIndex = sliceOf(global)
            guard sliceIndex < tripAllowed.count, let allowed = tripAllowed[sliceIndex] else { return true }
            return allowed[global - tripOffsets[sliceIndex]]
        }
        func consider(before: Int, arrival: Int, previousNode: Int, junction: Int, after: Int, afterEnds: TripEnds) {
            let wait = afterEnds.firstDeparture - arrival
            if wait < 0 || wait > SharedData.JourneyPlanner.maximumInSeatWaitMinutes { return }
            // A vehicle that turns back (Mendrisio, a funicular's top station) is not a journey to stay on.
            if afterEnds.secondNode == previousNode { return }
            // An avoided kind of train, or one not accessible, is never ridden through.
            if !allowed(before) || !allowed(after) { return }
            candidates.append((wait: wait, before: before, after: after, junction: junction, arrival: arrival))
        }
        // Blocks: trips of one slice with the same block_id, in order of departure.
        for (sliceIndex, slice) in slices.enumerated() {
            for block in slice.day.blockSequences {
                var before = ends(sliceIndex, block[0])
                for position in 1..<block.count {
                    let after = ends(sliceIndex, block[position])
                    if before.lastNode == after.firstNode {
                        consider(before: tripOffsets[sliceIndex] + block[position - 1], arrival: before.lastArrival,
                                 previousNode: before.previousNode, junction: before.lastNode,
                                 after: tripOffsets[sliceIndex] + block[position], afterEnds: after)
                    }
                    before = after
                }
            }
        }
        // Two packs: the same public number going on from a shared or matching station. A "number" many more runs share
        // is a line name and links nothing.
        let maxRunsPerNumber = 2 * slices.count
        for number in runs.tripsBySharedNumber.keys.sorted() {
            guard let numbered = runs.tripsBySharedNumber[number], numbered.count >= 2, numbered.count <= maxRunsPerNumber else { continue }
            let size = numbered.count
            var starts = [Int](repeating: 0, count: size)
            var finishes = [Int](repeating: 0, count: size)
            for index in 0..<size {
                let global = numbered[index]
                let sliceIndex = runs.sliceOfTrip[global]
                let slice = slices[sliceIndex]
                let local = global - tripOffsets[sliceIndex]
                let first = slice.day.tripFirstConnection[local]
                starts[index] = slice.day.departureMinute[first] + slice.offset
                finishes[index] = slice.day.arrivalMinute[first + slice.day.tripConnectionCount[local] - 1] + slice.offset
            }
            var known = [TripEnds?](repeating: nil, count: size)
            for i in 0..<size {
                for j in 0..<size where i != j {
                    let before = numbered[i]
                    let after = numbered[j]
                    // Only a run leaving while the other one runs, or at most the longest stay on board after it ends.
                    let leaves = starts[j]
                    if leaves < starts[i] || leaves > finishes[i] + SharedData.JourneyPlanner.maximumInSeatWaitMinutes { continue }
                    if representative[before] == representative[after] { continue }
                    let beforeSlice = runs.sliceOfTrip[before]
                    let afterSlice = runs.sliceOfTrip[after]
                    if slices[beforeSlice].day.sourceID == slices[afterSlice].day.sourceID { continue }
                    let beforeLocal = before - tripOffsets[beforeSlice]
                    let afterLocal = after - tripOffsets[afterSlice]
                    let beforeTrip = slices[beforeSlice].day.trips[beforeLocal]
                    let afterTrip = slices[afterSlice].day.trips[afterLocal]
                    // A coach and a train never share a vehicle, nor a EuroCity and a regional train.
                    if beforeTrip.mode != afterTrip.mode { continue }
                    let beforeBrand = JourneyPlannerRules.brand(publicNumber: beforeTrip.publicNumber, lineLabel: beforeTrip.lineLabel)
                    let afterBrand = JourneyPlannerRules.brand(publicNumber: afterTrip.publicNumber, lineLabel: afterTrip.lineLabel)
                    if !JourneyPlannerRules.brandsAgree(beforeBrand, afterBrand) { continue }
                    let beforeEnds = known[i] ?? ends(beforeSlice, beforeLocal)
                    known[i] = beforeEnds
                    let afterEnds = known[j] ?? ends(afterSlice, afterLocal)
                    known[j] = afterEnds
                    let start = afterEnds.firstNode
                    let from = beforeEnds.lastNode
                    if from == start || matchingPoints(from, start, footpaths: footpaths, slices: slices, runs: runs,
                                                       tripOffsets: tripOffsets, before: before, after: after, stopNodes: stopNodes) {
                        consider(before: before, arrival: beforeEnds.lastArrival, previousNode: beforeEnds.previousNode,
                                 junction: from, after: after, afterEnds: afterEnds)
                        continue
                    }
                    // A stop on the way: the first connection of `before` arriving at the start of `after`.
                    let day = slices[beforeSlice].day
                    let nodes = stopNodes[beforeSlice]
                    let first = day.tripFirstConnection[beforeLocal]
                    for connection in first..<(first + day.tripConnectionCount[beforeLocal] - 1) {
                        if nodes[day.arrivalStop[connection]] != start { continue }
                        if sameWayOn(slices, stopNodes: stopNodes, footpaths: footpaths, beforeSlice: beforeSlice, beforeLocal: beforeLocal,
                                     arrival: connection, afterSlice: afterSlice, afterLocal: afterLocal) {
                            consider(before: before, arrival: day.arrivalMinute[connection] + slices[beforeSlice].offset,
                                     previousNode: nodes[day.departureStop[connection]], junction: start, after: after, afterEnds: afterEnds)
                        }
                        break
                    }
                }
            }
        }
        guard !candidates.isEmpty else { return Continuations(successor: successor, group: representative, links: []) }
        var endsCache: [Int: TripEnds] = [:]
        func cachedEnds(_ global: Int) -> TripEnds {
            if let known = endsCache[global] { return known }
            let sliceIndex = sliceOf(global)
            let value = ends(sliceIndex, global - tripOffsets[sliceIndex])
            endsCache[global] = value
            return value
        }
        func dayTrip(_ global: Int) -> PlannerTrip {
            let sliceIndex = sliceOf(global)
            return slices[sliceIndex].day.trips[global - tripOffsets[sliceIndex]]
        }
        // Two copies of one run the merge could not pair: same number, leaving the same point at the same minute, or
        // arriving at the same point at the same minute.
        func sameRun(_ first: Int, _ second: Int, atStart: Bool) -> Bool {
            let a = cachedEnds(first)
            let b = cachedEnds(second)
            let samePoint = atStart ? a.firstNode == b.firstNode && a.firstDeparture == b.firstDeparture
                : a.lastNode == b.lastNode && a.lastArrival == b.lastArrival
            guard samePoint, let key = JourneyPlannerRules.runNumberKey(dayTrip(first).publicNumber) else { return false }
            return key == JourneyPlannerRules.runNumberKey(dayTrip(second).publicNumber)
        }
        // Keyed by the copies' group: a continuation found for one pack's copy of a run holds for the others.
        var predecessor = [Int](repeating: -1, count: tripCount)
        var links: [InSeatLink] = []
        let sorted = candidates.enumerated().sorted { a, b in
            a.element.wait != b.element.wait ? a.element.wait < b.element.wait : a.offset < b.offset
        }.map(\.element)
        for candidate in sorted {
            let beforeGroup = representative[candidate.before]
            let afterGroup = representative[candidate.after]
            if beforeGroup == afterGroup { continue }
            let linkedAfter = successor[beforeGroup]
            let linkedBefore = predecessor[afterGroup]
            if linkedAfter < 0, linkedBefore < 0 {
                successor[beforeGroup] = candidate.after
                predecessor[afterGroup] = candidate.before
            } else if linkedAfter >= 0, linkedBefore < 0, sameRun(linkedAfter, candidate.after, atStart: true) {
                // Another copy of the run already linked: one vehicle, so its copies go on as well.
                predecessor[afterGroup] = candidate.before
            } else if linkedBefore >= 0, linkedAfter < 0, sameRun(linkedBefore, candidate.before, atStart: false) {
                successor[beforeGroup] = candidate.after
            } else {
                continue
            }
            links.append(InSeatLink(before: candidate.before, after: candidate.after, junctionNode: candidate.junction,
                                    junctionArrival: candidate.arrival))
        }
        return Continuations(successor: successor, group: representative, links: links)
    }

    /// `after` (another pack) is the same train as `before` going on from where connection `arrival` of `before`
    /// arrives: it leaves there within the copy skew of `before`'s own departure, and one of its next stops is a stop
    /// `before` reaches afterwards (the same point or one a footpath joins) within the same skew.
    private func sameWayOn(
        _ slices: [Slice], stopNodes: [[Int]], footpaths: Footpaths, beforeSlice: Int, beforeLocal: Int, arrival: Int,
        afterSlice: Int, afterLocal: Int
    ) -> Bool {
        let skew = SharedData.JourneyPlanner.maximumCopySkewMinutes
        let day = slices[beforeSlice].day
        let offset = slices[beforeSlice].offset
        let afterDay = slices[afterSlice].day
        let afterOffset = slices[afterSlice].offset
        let beforeEnd = day.tripFirstConnection[beforeLocal] + day.tripConnectionCount[beforeLocal]
        let leaves = day.departureMinute[arrival + 1] + offset
        let afterFirst = afterDay.tripFirstConnection[afterLocal]
        if abs(afterDay.departureMinute[afterFirst] + afterOffset - leaves) > skew { return false }
        let beforeNodes = stopNodes[beforeSlice]
        let afterNodes = stopNodes[afterSlice]
        let lastOnward = min(afterFirst + SharedData.JourneyPlanner.onwardStops, afterFirst + afterDay.tripConnectionCount[afterLocal])
        for next in afterFirst..<lastOnward {
            let node = afterNodes[afterDay.arrivalStop[next]]
            let reaches = afterDay.arrivalMinute[next] + afterOffset
            for onward in (arrival + 1)..<beforeEnd {
                if abs(day.arrivalMinute[onward] + offset - reaches) > skew { continue }
                if footpaths.joined(beforeNodes[day.arrivalStop[onward]], node) { return true }
            }
        }
        return false
    }

    /// Two transfer points of two packs that are one station under two identities: a footpath joins them and the names
    /// agree.
    private func matchingPoints(
        _ from: Int, _ to: Int, footpaths: Footpaths, slices: [Slice], runs: MergedRuns, tripOffsets: [Int], before: Int, after: Int,
        stopNodes: [[Int]]
    ) -> Bool {
        var joined = false
        for index in footpaths.start[from]..<footpaths.start[from + 1] where footpaths.target[index] == to { joined = true }
        guard joined else { return false }
        func name(_ trip: Int, last: Bool) -> String? {
            let sliceIndex = runs.sliceOfTrip[trip]
            let slice = slices[sliceIndex]
            let local = trip - tripOffsets[sliceIndex]
            let first = slice.day.tripFirstConnection[local]
            let connection = last ? first + slice.day.tripConnectionCount[local] - 1 : first
            let stop = last ? slice.day.arrivalStop[connection] : slice.day.departureStop[connection]
            return stopNodes[sliceIndex][stop] == (last ? from : to) ? slice.coverage.stops.names[stop] : nil
        }
        guard let first = name(before, last: true), let second = name(after, last: false) else { return false }
        return !JourneyPlannerRules.normalizedVariants(first).isDisjoint(with: JourneyPlannerRules.normalizedVariants(second))
    }

    /// The scan's in-seat links: per sorted connection, the trips the vehicle goes on as after it.
    private func inSeatContinuations(
        _ links: [InSeatLink], representative: [Int], trip: [Int], arrivalNode: [Int], arrivalMinute: [Int]
    ) -> (start: [Int], trip: [Int], next: [Int])? {
        guard !links.isEmpty else { return nil }
        var byTrip: [Int: [InSeatLink]] = [:]
        var linked = [Bool](repeating: false, count: representative.count)
        for link in links {
            byTrip[representative[link.before], default: []].append(link)
            linked[representative[link.before]] = true
        }
        var start = [Int](repeating: -1, count: trip.count)
        var nextTrip: [Int] = []
        var next: [Int] = []
        for connection in trip.indices where linked[trip[connection]] {
            for link in byTrip[trip[connection]] ?? [] {
                if link.junctionNode != arrivalNode[connection] || link.junctionArrival != arrivalMinute[connection] { continue }
                let target = representative[link.after]
                var existing = start[connection]
                var duplicate = false
                while existing >= 0 {
                    if nextTrip[existing] == target { duplicate = true }
                    existing = next[existing]
                }
                if duplicate { continue }
                next.append(start[connection])
                nextTrip.append(target)
                start[connection] = nextTrip.count - 1
            }
        }
        return (start: start, trip: nextTrip, next: next)
    }

    // MARK: - Options

    private func toOption(
        _ journey: ScannedJourney, graph: SearchGraph, slices: [Slice], origin: Endpoint, destination: Endpoint, reference: Date,
        originAccessible: Bool, destinationAccessible: Bool
    ) -> JourneyPlanOption {
        let timetable = graph.timetable
        func at(_ minute: Int) -> Date { reference.addingTimeInterval(TimeInterval(minute * 60)) }
        var usedPacks: [String] = []
        func use(_ source: String) { if !usedPacks.contains(source) { usedPacks.append(source) } }
        var stopsInSearch: [PlannerPackStops] = []
        for slice in slices where !stopsInSearch.contains(where: { $0 === slice.coverage.stops }) { stopsInSearch.append(slice.coverage.stops) }
        func nodeAccessible(_ key: String) -> Bool { stopsInSearch.contains { $0.nodeHasAccessibleStop(key) } }
        var boardNodes: [String] = []
        var alightNodes: [String] = []
        var boardPlaces: [(id: String, label: String)] = []
        var alightPlaces: [(id: String, label: String)] = []
        var vehicleLegs: [JourneyPlanLeg] = []
        for (index, leg) in journey.legs.enumerated() {
            let boardSlice = graph.sliceOf[leg.boardConnection]
            let alightSlice = graph.sliceOf[leg.alightConnection]
            let day = slices[boardSlice].day
            // A run merged across packs can be left on a connection only the other pack publishes.
            let alightDay = slices[alightSlice].day
            let board = graph.connectionOf[leg.boardConnection]
            let alight = graph.connectionOf[leg.alightConnection]
            let trip = day.trips[day.connectionTrip[board]]
            use(day.sourceID)
            use(alightDay.sourceID)
            let boardStop = day.departureStop[board]
            let alightStop = alightDay.arrivalStop[alight]
            let boardNode = graph.nodeKeys[timetable.departureNode[leg.boardConnection]]
            let alightNode = graph.nodeKeys[timetable.arrivalNode[leg.alightConnection]]
            let boardPlace = place(boardNode, slices[boardSlice].coverage, boardStop)
            let alightPlace = place(alightNode, slices[alightSlice].coverage, alightStop)
            boardPlaces.append(boardPlace)
            alightPlaces.append(alightPlace)
            boardNodes.append(boardNode)
            alightNodes.append(alightNode)
            // «Resta a bordo»: the trips the vehicle runs on until the alighting connection, after the boarded one.
            let boardTrip = graph.tripOffsets[boardSlice] + day.connectionTrip[board]
            let alightTrip = graph.tripOffsets[alightSlice] + alightDay.connectionTrip[alight]
            let alightDeparture = timetable.departureMinute[leg.alightConnection]
            var continuations: [JourneyInSeatContinuation] = []
            var vehicleAccessible = trip.wheelchair == PlannerWheelchair.yes
            var bikes = trip.bikesAllowed
            var current = graph.runGroup[boardTrip]
            let target = graph.runGroup[alightTrip]
            while current != target, continuations.count < SharedData.JourneyPlanner.maximumContinuations {
                let next = graph.successor[current]
                if next < 0 { break }
                let nextSlice = graph.sliceOfTrip(next)
                let nextDay = slices[nextSlice].day
                let nextLocal = next - graph.tripOffsets[nextSlice]
                let nextTrip = nextDay.trips[nextLocal]
                let first = nextDay.tripFirstConnection[nextLocal]
                // Left before the vehicle goes on as the next trip.
                if nextDay.departureMinute[first] + slices[nextSlice].offset > alightDeparture { break }
                let stop = nextDay.departureStop[first]
                let station = place(slices[nextSlice].coverage.stops.nodeKeys[stop], slices[nextSlice].coverage, stop)
                let naming = JourneyPlannerRules.numberAndLine(mode: nextTrip.mode, publicNumber: nextTrip.publicNumber, lineLabel: nextTrip.lineLabel)
                use(nextDay.sourceID)
                continuations.append(JourneyInSeatContinuation(
                    stationID: station.id, stationLabel: station.label, runID: Self.runID(nextDay.sourceID, nextTrip.externalID, nextDay.serviceDate),
                    publicNumber: naming.number, lineLabel: naming.line, operatorName: nextTrip.operatorName,
                    serviceKind: JourneyPlannerRules.serviceKind(mode: nextTrip.mode, category: nextTrip.category, line: naming.line)
                ))
                vehicleAccessible = vehicleAccessible && nextTrip.wheelchair == PlannerWheelchair.yes
                bikes = Self.bikesAcrossTrips(bikes, nextTrip.bikesAllowed)
                current = graph.runGroup[next]
            }
            // Left on another pack's copy of the run (merged, not continued): that copy must be accessible too.
            if alightTrip != boardTrip {
                let alightCopy = alightDay.trips[alightDay.connectionTrip[alight]]
                vehicleAccessible = vehicleAccessible && alightCopy.wheelchair == PlannerWheelchair.yes
                if continuations.isEmpty { bikes = Self.bikesOfCopies(bikes, alightCopy.bikesAllowed) }
            }
            let wheelchair = JourneyLegWheelchair(
                vehicle: vehicleAccessible,
                boarding: slices[boardSlice].coverage.stops.stopAccessible(boardStop),
                alighting: slices[alightSlice].coverage.stops.stopAccessible(alightStop)
            )
            let from: (id: String, label: String) = index == 0 && journey.accessWalk == nil
                ? (id: request.origin.id, label: origin.name) : boardPlace
            let to: (id: String, label: String) = index == journey.legs.count - 1 && journey.egressWalk == nil
                ? (id: request.destination.id, label: destination.name) : alightPlace
            let naming = JourneyPlannerRules.numberAndLine(mode: trip.mode, publicNumber: trip.publicNumber, lineLabel: trip.lineLabel)
            vehicleLegs.append(JourneyPlanLeg(
                mode: JourneyLegMode(trip.mode),
                runID: Self.runID(day.sourceID, trip.externalID, day.serviceDate),
                sourceID: day.sourceID,
                tripID: trip.externalID,
                originID: from.id, originLabel: from.label,
                destinationID: to.id, destinationLabel: to.label,
                departure: at(timetable.departureMinute[leg.boardConnection]),
                arrival: at(timetable.arrivalMinute[leg.alightConnection]),
                publicNumber: naming.number,
                hasTrainNumber: trip.hasTrainNumber && !trip.mode.isUrban,
                lineLabel: naming.line ?? (trip.mode.isUrban ? trip.longName : nil),
                operatorName: trip.operatorName,
                serviceKind: JourneyPlannerRules.serviceKind(mode: trip.mode, category: trip.category, line: naming.line),
                serviceDate: day.serviceDate,
                wheelchair: wheelchair,
                bikesAllowed: bikes,
                continuations: continuations,
                // What the scan asked for a change at the boarding point, so the live check of the change asks the same.
                changeMinutes: timetable.transferMinutes[timetable.departureNode[leg.boardConnection]]
            ))
        }
        func walk(from: (id: String, label: String), to: (id: String, label: String), start: Int, minutes: Int, fromAccessible: Bool,
                  toAccessible: Bool) -> JourneyPlanLeg {
            JourneyPlanLeg(
                mode: .walk, runID: nil, sourceID: nil, tripID: nil, originID: from.id, originLabel: from.label,
                destinationID: to.id, destinationLabel: to.label, departure: at(start), arrival: at(start + minutes),
                publicNumber: nil, hasTrainNumber: false, lineLabel: nil, operatorName: nil, serviceKind: .unknown, serviceDate: nil,
                wheelchair: JourneyLegWheelchair(vehicle: true, boarding: fromAccessible, alighting: toAccessible),
                bikesAllowed: nil, continuations: [], changeMinutes: nil
            )
        }
        var legs: [JourneyPlanLeg] = []
        if let access = journey.accessWalk {
            let first = timetable.departureMinute[journey.legs[0].boardConnection]
            legs.append(walk(from: (id: request.origin.id, label: origin.name), to: boardPlaces[0], start: first - access.minutes, minutes: access.minutes,
                             fromAccessible: originAccessible, toAccessible: nodeAccessible(boardNodes[0])))
        }
        for (index, leg) in vehicleLegs.enumerated() {
            if index > 0, index - 1 < journey.transferWalks.count, let transfer = journey.transferWalks[index - 1] {
                let arrived = timetable.arrivalMinute[journey.legs[index - 1].alightConnection]
                legs.append(walk(from: alightPlaces[index - 1], to: boardPlaces[index], start: arrived, minutes: transfer.minutes,
                                 fromAccessible: nodeAccessible(alightNodes[index - 1]), toAccessible: nodeAccessible(boardNodes[index])))
            }
            legs.append(leg)
        }
        if let egress = journey.egressWalk {
            let arrived = timetable.arrivalMinute[journey.legs[journey.legs.count - 1].alightConnection]
            legs.append(walk(from: alightPlaces[alightPlaces.count - 1], to: (id: request.destination.id, label: destination.name), start: arrived,
                             minutes: egress.minutes, fromAccessible: nodeAccessible(alightNodes[alightNodes.count - 1]),
                             toAccessible: destinationAccessible))
        }
        let identity = vehicleLegs.map { "\($0.runID ?? "")@\(Int($0.departure.timeIntervalSince1970))" }.joined(separator: "|")
        return JourneyPlanOption(
            id: "gtfs-journey:" + identity, originID: request.origin.id, destinationID: request.destination.id,
            departure: legs[0].departure, arrival: legs[legs.count - 1].arrival, legs: legs, sourceIDs: usedPacks
        )
    }

    /// The station of a transfer point: the catalogue station, else the pack's own stop (`gtfs:<pack>:<stop>`), named as
    /// Android names it (a city stop as published, a railway stop after «Stazione di»).
    private func place(_ nodeKey: String, _ coverage: PackCoverage, _ stop: Int) -> (id: String, label: String) {
        if let station = request.stations[nodeKey] { return (station.id, station.name) }
        let stops = coverage.stops
        let name = stops.names[stop]
        let label = coverage.pack.isUrban ? name : JourneyPlannerRules.displayName(name)
        return (UrbanStops.stationID(sourceID: stops.sourceID, stopID: stops.externalIDs[stop]), label)
    }

    static func runID(_ sourceID: String, _ tripID: String, _ serviceDate: Int) -> String {
        let text = String(format: "%04d-%02d-%02d", serviceDate / 10_000, serviceDate / 100 % 100, serviceDate % 100)
        return "\(sourceID):\(tripID):\(text)"
    }

    /// Bikes on one vehicle run as several trips: not allowed as soon as one trip says so, allowed when both do.
    static func bikesAcrossTrips(_ first: Bool?, _ next: Bool?) -> Bool? {
        if first == false || next == false { return false }
        if first == true, next == true { return true }
        return nil
    }

    /// Bikes of two packs' copies of one run: a published value wins over an unknown one; two different ones are unknown.
    static func bikesOfCopies(_ first: Bool?, _ second: Bool?) -> Bool? {
        guard let first else { return second }
        if second == nil || second == first { return first }
        return nil
    }
}

/// What the travel preferences take out of a search (Android's `SearchFilter`).
struct SearchFilter {
    let avoided: Set<TravelMode>
    let wheelchairOnly: Bool
    let preferLessWalking: Bool

    init(_ preferences: JourneyPreferences?) {
        avoided = preferences?.avoidedModes ?? []
        wheelchairOnly = preferences?.wheelchairOnly ?? false
        preferLessWalking = preferences?.ranking == .leastWalking
    }

    var active: Bool { !avoided.isEmpty || wheelchairOnly }

    func allows(_ trip: PlannerTrip) -> Bool {
        if wheelchairOnly, trip.wheelchair != PlannerWheelchair.yes { return false }
        if avoided.isEmpty { return true }
        let naming = JourneyPlannerRules.numberAndLine(mode: trip.mode, publicNumber: trip.publicNumber, lineLabel: trip.lineLabel)
        let kind = JourneyPlannerRules.serviceKind(mode: trip.mode, category: trip.category, line: naming.line)
        guard let mode = TravelMode.of(mode: trip.mode, serviceKind: kind, operatorName: trip.operatorName) else { return true }
        return !avoided.contains(mode)
    }
}
