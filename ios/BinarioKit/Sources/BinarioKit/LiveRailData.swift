import Foundation

/// The live sources together, in Android's order (`LocalOnlyRailDataPolicy`):
/// - train status by number: ViaggiaTreno, then Italo (`trainNumberSearchProviders`), asked
///   together; the runs of both are kept, ViaggiaTreno's first;
/// - departures: the RFI monitor, Italo, ViaggiaTreno (`stationBoardProviders`), asked together and
///   merged field by field with Android's per-category source scores (`StationBoardAggregateService`).
/// The bundled timetable stays the fallback: nothing here invents a train, a time or a platform.
public struct LiveRailData: LiveTrainStatusProviding, LiveDeparturesProviding {
    public let statusSources: [any LiveTrainStatusSource]
    public let boardSources: [any LiveDepartureBoardSource]
    /// Android's `TrainNumberSearchService.providerTimeoutMillis` (15 s) and
    /// `StationBoardAggregateService.providerTimeoutMillis` (12 s).
    let statusTimeout: TimeInterval
    let boardTimeout: TimeInterval

    public init(
        statusSources: [any LiveTrainStatusSource], boardSources: [any LiveDepartureBoardSource],
        statusTimeout: TimeInterval = 15, boardTimeout: TimeInterval = 12
    ) {
        self.statusSources = statusSources
        self.boardSources = boardSources
        self.statusTimeout = statusTimeout
        self.boardTimeout = boardTimeout
    }

    /// The iPhone's sources: ViaggiaTreno, RFI and Italo over `transport` (ephemeral `URLSession`s).
    public static func standard(
        transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) -> LiveRailData {
        let viaggiaTreno = ViaggiaTrenoLiveSource(transport: transport, pauses: pauses, now: now)
        let italo = ItaloLiveSource(transport: transport, pauses: pauses, now: now)
        let rfi = RfiLiveSource(transport: transport, pauses: pauses, now: now)
        return LiveRailData(statusSources: [viaggiaTreno, italo], boardSources: [rfi, italo, viaggiaTreno])
    }

    public func trainStatus(_ request: LiveTrainStatusRequest) async -> LiveTrainStatusResult {
        let query = LiveTrainNumberQuery(request.number)
        guard query.isValid else { return .unavailable(.invalidRequest) }
        guard !statusSources.isEmpty else { return .unavailable(.notCovered) }
        let sources = statusSources
        let timeout = statusTimeout
        let answers = await LiveTasks.boundedMap(Array(sources.indices), maximumConcurrent: sources.count) { index in
            await LiveTasks.withDeadline(seconds: timeout) { await sources[index].runs(for: request) }
                ?? LiveSourceAnswer<[LiveRunStatus]>.unavailable(.sourceUnavailable)
        }
        if Task.isCancelled { return .unavailable(.cancelled) }

        var best: [String: LiveRunStatus] = [:]
        var order: [String] = []
        var unavailable: [LiveSource: LiveUnavailableReason] = [:]
        var reasons: [LiveUnavailableReason] = []
        var complete = true
        for (index, answer) in answers.enumerated() {
            switch answer {
            case let .answered(found, answeredInFull):
                if !answeredInFull { complete = false }
                for run in found where query.matches(run.number) {
                    guard let kept = best[run.id] else {
                        best[run.id] = run
                        order.append(run.id)
                        continue
                    }
                    // `TrainNumberSearchService`: a verified run first, then the latest observation.
                    if Self.rank(run) > Self.rank(kept) { best[run.id] = run }
                }
            case let .unavailable(reason):
                unavailable[sources[index].source] = reason
                reasons.append(reason)
                complete = false
            }
        }
        let runs = order.enumerated().compactMap { offset, id in best[id].map { (offset, $0) } }.sorted { first, second in
            let (a, b) = (first.1, second.1)
            if a.scheduledDeparture != b.scheduledDeparture { return a.scheduledDeparture < b.scheduledDeparture }
            if a.originName != b.originName { return a.originName < b.originName }
            if a.observation.source.displayName != b.observation.source.displayName {
                return a.observation.source.displayName < b.observation.source.displayName
            }
            return first.0 < second.0
        }.map { $0.1 }
        guard runs.isEmpty else {
            return .available(LiveTrainStatusLookup(runs: runs, unavailableSources: unavailable, isComplete: complete))
        }
        return .unavailable(Self.reason(reasons, answered: reasons.count < answers.count))
    }

    public func departures(_ request: LiveDeparturesRequest) async -> LiveDeparturesResult {
        guard !boardSources.isEmpty else { return .unavailable(.notCovered) }
        // `StationBoardAggregateService`: ten minutes back, six hours ahead, late trains from two hours back.
        let window = LiveBoardWindow(
            station: request.station, from: request.from.addingTimeInterval(-600),
            to: request.from.addingTimeInterval(6 * 3_600), lateSince: request.from.addingTimeInterval(-2 * 3_600)
        )
        let sources = boardSources
        let timeout = boardTimeout
        let answers = await LiveTasks.boundedMap(Array(sources.indices), maximumConcurrent: sources.count) { index in
            await LiveTasks.withDeadline(seconds: timeout) { await sources[index].departures(for: window) }
                ?? LiveSourceAnswer<[LiveDeparture]>.unavailable(.sourceUnavailable)
        }
        if Task.isCancelled { return .unavailable(.cancelled) }
        var boards: [(source: LiveSource, departures: [LiveDeparture])] = []
        var unavailable: [LiveSource: LiveUnavailableReason] = [:]
        var reasons: [LiveUnavailableReason] = []
        for (index, answer) in answers.enumerated() {
            switch answer {
            case let .answered(rows, _):
                boards.append((sources[index].source, rows))
            case let .unavailable(reason):
                unavailable[sources[index].source] = reason
                reasons.append(reason)
            }
        }
        guard !boards.isEmpty else {
            return .unavailable(reasons.allSatisfy { $0 == .notCovered } ? .notCovered : Self.reason(reasons, answered: false))
        }
        var warnings: Set<LiveBoardWarning> = []
        let merged = LiveBoardAggregator.merge(boards, window: window, warnings: &warnings)
        if reasons.contains(where: { $0 != .notCovered }) { warnings.insert(.realtimeUnavailable) }
        let fetchedAt = boards.flatMap { $0.departures }.map { $0.observation.fetchedAt }.max() ?? request.from
        return .available(LiveDepartureBoard(
            stationID: request.station.id, from: window.from, to: window.to, departures: merged,
            sources: boards.map { $0.source }, unavailableSources: unavailable, warnings: warnings, fetchedAt: fetchedAt
        ))
    }

    private static func rank(_ run: LiveRunStatus) -> (Int, Double) {
        (run.operationalStatusVerified ? 1 : 0, run.operationalStatusVerified ? run.observation.observedAt.timeIntervalSince1970 : 0)
    }

    /// The reason to give when no source has a value: a cancellation, else the first real failure.
    private static func reason(_ reasons: [LiveUnavailableReason], answered: Bool) -> LiveUnavailableReason {
        if reasons.contains(.cancelled) { return .cancelled }
        if let failure = reasons.first(where: { $0 != .notCovered && $0 != .notFound }) { return failure }
        if answered || reasons.contains(.notFound) { return .notFound }
        return reasons.first ?? .notFound
    }
}

/// Android's `RailSourceSelectionPolicy` for the three live sources: the best source is not the
/// same for every value.
enum LiveSourcePolicy {
    enum Category {
        /// Expected times and running states.
        case trainRealtime
        /// Platforms, destinations and the row itself.
        case stationBoard
    }

    static func score(_ category: Category, _ source: LiveSource, operatorHint: String?) -> Int {
        let base: Int
        switch (category, source) {
        case (.trainRealtime, .italo): base = 130
        case (.trainRealtime, .viaggiaTreno): base = 128
        case (.trainRealtime, .rfiStationBoard): base = 126
        case (.stationBoard, .rfiStationBoard): base = 150
        case (.stationBoard, .italo): base = 128
        case (.stationBoard, .viaggiaTreno): base = 128
        }
        let hint = LiveText.boardKey(operatorHint ?? "")
        guard !hint.isEmpty else { return base }
        let affinity = category == .trainRealtime ? 20 : 10
        switch source {
        case .italo: return hint.contains("italo") ? base + affinity : base
        case .viaggiaTreno:
            return ["trenitalia", "frecce", "regionale"].contains(where: { hint.contains($0) }) ? base + affinity : base
        case .rfiStationBoard: return base
        }
    }
}

/// Android's `StationBoardAggregateService.aggregate`: the rows of the same public train are put
/// together (same number, scheduled within five minutes, same destination, no known operators or
/// runs of one source that differ) and each value is taken from the best source for it.
enum LiveBoardAggregator {
    static let identityTolerance: TimeInterval = 300
    static let conflictTolerance: TimeInterval = 300
    static let maximumDepartures = 300

    private struct Candidate {
        let order: Int
        let departure: LiveDeparture
    }

    static func merge(
        _ boards: [(source: LiveSource, departures: [LiveDeparture])], window: LiveBoardWindow,
        warnings: inout Set<LiveBoardWarning>
    ) -> [LiveDeparture] {
        let providerOrder = boards.map { $0.source }
        var flattened: [Candidate] = []
        for board in boards {
            for departure in board.departures { flattened.append(Candidate(order: flattened.count, departure: departure)) }
        }
        let candidates = flattened.sorted {
            $0.departure.scheduled == $1.departure.scheduled ? $0.order < $1.order : $0.departure.scheduled < $1.departure.scheduled
        }
        var groups: [[Candidate]] = []
        for candidate in candidates {
            if let index = groups.firstIndex(where: { samePublicRun($0[0], candidate) }) {
                groups[index].append(candidate)
            } else {
                groups.append([candidate])
            }
        }
        if hasAmbiguousIdentity(candidates) { warnings.insert(.identityAmbiguous) }
        var merged: [(Int, LiveDeparture)] = []
        for (index, group) in groups.enumerated() {
            if hasConflict(group) { warnings.insert(.sourceConflict) }
            let departure = merge(group, providerOrder: providerOrder)
            if window.keeps(scheduled: departure.scheduled, expected: departure.expected?.value) { merged.append((index, departure)) }
        }
        return merged.sorted { first, second in
            let a = first.1.expected?.value ?? first.1.scheduled
            let b = second.1.expected?.value ?? second.1.scheduled
            return a == b ? first.0 < second.0 : a < b
        }.prefix(maximumDepartures).map { $0.1 }
    }

    private static func samePublicRun(_ first: Candidate, _ second: Candidate) -> Bool {
        let firstNumber = LiveText.boardKey(first.departure.number)
        let secondNumber = LiveText.boardKey(second.departure.number)
        if !firstNumber.isEmpty, !secondNumber.isEmpty {
            return sameNumberTimeAndEndpoint(first, second) && !knownOperatorsDiffer(first, second)
                && !sameSourceReportsDifferentRun(first, second)
        }
        return first.departure.id == second.departure.id && withinTolerance(first, second)
            && LiveText.boardKey(first.departure.destination) == LiveText.boardKey(second.departure.destination)
    }

    private static func withinTolerance(_ first: Candidate, _ second: Candidate) -> Bool {
        abs(first.departure.scheduled.timeIntervalSince(second.departure.scheduled)) <= identityTolerance
    }

    private static func sameNumberTimeAndEndpoint(_ first: Candidate, _ second: Candidate) -> Bool {
        let firstNumber = LiveText.boardKey(first.departure.number)
        return !firstNumber.isEmpty && firstNumber == LiveText.boardKey(second.departure.number) && withinTolerance(first, second)
            && LiveText.boardKey(first.departure.destination) == LiveText.boardKey(second.departure.destination)
    }

    private static func knownOperatorsDiffer(_ first: Candidate, _ second: Candidate) -> Bool {
        let firstOperator = LiveText.boardKey(first.departure.operatorID ?? "")
        let secondOperator = LiveText.boardKey(second.departure.operatorID ?? "")
        return !firstOperator.isEmpty && !secondOperator.isEmpty && firstOperator != secondOperator
    }

    /// Two different runs listed by one source are two trains.
    private static func sameSourceReportsDifferentRun(_ first: Candidate, _ second: Candidate) -> Bool {
        first.departure.id != second.departure.id && !Set(first.departure.sources).isDisjoint(with: second.departure.sources)
    }

    private static func hasAmbiguousIdentity(_ candidates: [Candidate]) -> Bool {
        let byNumber = Dictionary(grouping: candidates) { LiveText.boardKey($0.departure.number) }
        for (_, sameNumber) in byNumber where sameNumber.count > 1 {
            for firstIndex in sameNumber.indices {
                for secondIndex in sameNumber.indices where secondIndex > firstIndex {
                    let first = sameNumber[firstIndex]
                    let second = sameNumber[secondIndex]
                    if sameNumberTimeAndEndpoint(first, second),
                       knownOperatorsDiffer(first, second) || sameSourceReportsDifferentRun(first, second) { return true }
                }
            }
        }
        return false
    }

    private static func hasConflict(_ group: [Candidate]) -> Bool {
        let expected = group.compactMap { $0.departure.expected?.value }
        if let earliest = expected.min(), let latest = expected.max(), latest.timeIntervalSince(earliest) > conflictTolerance { return true }
        let platforms = Set(group.compactMap { $0.departure.platform?.value }.map(LiveText.boardKey).filter { !$0.isEmpty })
        if platforms.count > 1 { return true }
        return Set(group.compactMap { $0.departure.status?.value }).count > 1
    }

    /// The observation that speaks best for `departure` in `category`: its values' sources, then its
    /// board's, by score and then by observation time (Android's `bestSource`).
    private static func bestSource(_ departure: LiveDeparture, _ category: LiveSourcePolicy.Category, hint: String?) -> LiveObservation {
        var seen: Set<LiveSource> = []
        let observations = [departure.expected?.observation, departure.platform?.observation, departure.status?.observation, departure.observation]
            .compactMap { $0 }.filter { seen.insert($0.source).inserted }
        return firstMaximum(observations, category, hint: hint) { $0 } ?? departure.observation
    }

    /// The first item with the highest score of its source in `category`, then the latest
    /// observation (Kotlin's `maxWith`, which keeps the first of equal items).
    private static func firstMaximum<Item>(
        _ items: [Item], _ category: LiveSourcePolicy.Category, hint: String?, _ observation: (Item) -> LiveObservation
    ) -> Item? {
        var best: Item?
        var bestKey: (Int, Double)?
        for item in items {
            let seen = observation(item)
            let itemKey = (LiveSourcePolicy.score(category, seen.source, operatorHint: hint), seen.observedAt.timeIntervalSince1970)
            if let current = bestKey, !(itemKey > current) { continue }
            best = item
            bestKey = itemKey
        }
        return best
    }

    private static func merge(_ group: [Candidate], providerOrder: [LiveSource]) -> LiveDeparture {
        let hint = group.compactMap(\.departure.operatorID).first
        let rowSource: (Candidate) -> LiveObservation = { bestSource($0.departure, .stationBoard, hint: hint) }
        let base = firstMaximum(group, .stationBoard, hint: hint, rowSource) ?? group[0]
        let expected = firstMaximum(group.compactMap(\.departure.expected), .trainRealtime, hint: hint) { $0.observation }
        let platformRows = group.compactMap { candidate in candidate.departure.platform.map { (candidate, $0) } }
        let platform = firstMaximum(platformRows, .stationBoard, hint: hint) { $0.1.observation }
        let status = firstMaximum(group.compactMap(\.departure.status), .trainRealtime, hint: hint) { $0.observation }
        let label = firstMaximum(group.filter { !$0.departure.destination.isEmpty }, .stationBoard, hint: hint, rowSource)
        let row = base.departure
        let sources = Set(group.flatMap(\.departure.sources))
        let category = row.category.isEmpty ? (group.first { !$0.departure.category.isEmpty }?.departure.category ?? "") : row.category
        return LiveDeparture(
            id: row.id, number: row.number, operatorID: row.operatorID ?? hint, category: category,
            destination: label?.departure.destination ?? row.destination, scheduled: row.scheduled, expected: expected,
            platform: platform?.1,
            // The timetable platform of the source that gave the platform: two sources never make a change together.
            scheduledPlatform: platform?.0.departure.scheduledPlatform,
            status: status, sources: providerOrder.filter { sources.contains($0) },
            viaggiaTrenoRun: row.viaggiaTrenoRun ?? group.lazy.compactMap(\.departure.viaggiaTrenoRun).first,
            observation: row.observation
        )
    }
}

extension LiveTrainStatusLookup {
    /// The run `request` means (Android's `FavouriteTrainMatcher.pick`): the only run with its
    /// number; else the only one leaving from, then the only one calling at, its station code; else
    /// the only one from its origin name. Nil when the runs cannot be told apart.
    public func run(for request: LiveTrainStatusRequest) -> LiveRunStatus? {
        let key = NetworkReliability.numberKey(request.number)
        let numbered = runs.filter { $0.numberKey == key }
        let sameNumber = numbered.isEmpty ? runs : numbered
        if sameNumber.count == 1 { return sameNumber[0] }
        if let code = request.stationCode {
            let leaving = sameNumber.filter { $0.originCode == code }
            if leaving.count == 1 { return leaving[0] }
            let calling = sameNumber.filter { run in run.stops.contains { $0.stationCode == code && !$0.skipped } }
            if calling.count == 1 { return calling[0] }
        }
        let origin = LiveText.asciiAlphanumerics((request.originName ?? "").uppercased())
        guard !origin.isEmpty else { return nil }
        let fromOrigin = sameNumber.filter { LiveText.asciiAlphanumerics($0.originName.uppercased()) == origin }
        return fromOrigin.count == 1 ? fromOrigin[0] : nil
    }
}

/// Live values over the bundled timetable rows, which keep their scheduled data.
public enum LiveOverlay {
    /// Android's identity tolerance between two boards' rows of one train.
    public static let identityTolerance: TimeInterval = 300

    /// The live row of the train numbered `number` (any form: "RE_11 - 2153", "FR 9558") leaving
    /// at `scheduled`: the same number key, scheduled within five minutes; the closer one when
    /// two are. Rows equally close are one train only when the board could not merge them (other
    /// sources, the same scheduled time: a destination spelt differently): the row of the board's
    /// best source then. Nil when none is close, or two equally close rows may be two trains.
    public static func departure(number: String, scheduled: Date, in departures: [LiveDeparture]) -> LiveDeparture? {
        let key = NetworkReliability.numberKey(number)
        let near = departures.filter {
            $0.numberKey == key && abs($0.scheduled.timeIntervalSince(scheduled)) <= identityTolerance
        }.sorted { abs($0.scheduled.timeIntervalSince(scheduled)) < abs($1.scheduled.timeIntervalSince(scheduled)) }
        guard let closest = near.first else { return nil }
        let distance = abs(closest.scheduled.timeIntervalSince(scheduled))
        let tied = near.filter { abs($0.scheduled.timeIntervalSince(scheduled)) == distance }
        guard tied.count > 1 else { return closest }
        return bestRowOfOneTrain(tied)
    }

    /// Rows of one train that sources listed apart: all at the same scheduled time and no source
    /// in two of them (one source listing two rows means two trains). The row whose source scores
    /// best for the row itself (`LiveSourcePolicy`, `.stationBoard`: RFI first); nil on a tie.
    static func bestRowOfOneTrain(_ rows: [LiveDeparture]) -> LiveDeparture? {
        guard let first = rows.first, rows.allSatisfy({ $0.scheduled == first.scheduled }) else { return nil }
        var seen: Set<LiveSource> = []
        for row in rows {
            guard seen.isDisjoint(with: row.sources) else { return nil }
            seen.formUnion(row.sources)
        }
        let hint = rows.compactMap(\.operatorID).first
        let scores: [Int] = rows.map { row in
            row.sources.map { LiveSourcePolicy.score(.stationBoard, $0, operatorHint: hint) }.max() ?? 0
        }
        guard let best = scores.max(), scores.filter({ $0 == best }).count == 1,
              let index = scores.firstIndex(of: best) else { return nil }
        return rows[index]
    }

    /// The live row of a bundled timetable train: its "HH:mm" departure on `day` (Italian time;
    /// "24:10" and later are past midnight).
    public static func departure(for service: TrainService, on day: Date, in board: LiveDepartureBoard) -> LiveDeparture? {
        guard service.hasTrainNumber, let scheduled = scheduledDate(service.scheduledDeparture, on: day) else { return nil }
        return departure(number: service.number, scheduled: scheduled, in: board.departures)
    }

    /// The stop of `run` at a station: by its railway code, else the only stop with its name.
    public static func stop(in run: LiveRunStatus, stationCode: String?, stationName: String?) -> LiveStopStatus? {
        if let code = stationCode, NetworkReliability.isStationCode(code) {
            let coded = run.stops.filter { $0.stationCode == code }
            if coded.count == 1 { return coded[0] }
        }
        let name = LiveText.catalogueStationKey(stationName ?? "", sanPrefix: true)
        guard !name.isEmpty else { return nil }
        let named = run.stops.filter { LiveText.catalogueStationKey($0.stationName, sanPrefix: true) == name }
        return named.count == 1 ? named[0] : nil
    }

    /// "HH:mm" (hours up to 47) on the Italian day of `day`.
    static func scheduledDate(_ value: String, on day: Date) -> Date? {
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, (1...2).contains(parts[0].count), parts[1].count == 2,
              let hours = Int(parts[0]), let minutes = Int(parts[1]), (0...47).contains(hours), (0...59).contains(minutes),
              let sameDay = LiveRailTime.date(onDayOf: day, hour: hours % 24, minute: minutes) else { return nil }
        return hours >= 24 ? LiveRailTime.addingDays(1, to: sameDay) : sameDay
    }
}
