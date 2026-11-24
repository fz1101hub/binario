import Foundation

/// The live sources together, in Android's order (`LocalOnlyRailDataPolicy`):
/// - train status by number: ViaggiaTreno, then Italo (`trainNumberSearchProviders`), asked
///   together; the runs of both are kept, ViaggiaTreno's first;
/// - departures and arrivals: the RFI monitor, Italo (departures only), ViaggiaTreno and the Swiss
///   boards of transport.opendata.ch (departures only) (`stationBoardProviders`), asked together and
///   merged field by field with Android's per-category source scores (`StationBoardAggregateService`);
/// - the run of a board row: ViaggiaTreno (`stationBoardRunDetailProviders`);
/// - the notices of a train: ViaggiaTreno's «smart caring» (`trainNoticeProviders`).
/// The bundled timetable stays the fallback: nothing here invents a train, a time or a platform.
public struct LiveRailData: LiveTrainStatusProviding, LiveDeparturesProviding {
    public let statusSources: [any LiveTrainStatusSource]
    public let boardSources: [any LiveDepartureBoardSource]
    public let runDetailSources: [any LiveBoardRunSource]
    public let noticeSources: [any LiveTrainNoticeSource]
    /// Android's `TrainNumberSearchService.providerTimeoutMillis` (15 s) and
    /// `StationBoardAggregateService.providerTimeoutMillis` (12 s), which is also
    /// `StationBoardRunDetailService`'s.
    let statusTimeout: TimeInterval
    let boardTimeout: TimeInterval

    public init(
        statusSources: [any LiveTrainStatusSource], boardSources: [any LiveDepartureBoardSource],
        statusTimeout: TimeInterval = 15, boardTimeout: TimeInterval = 12,
        runDetailSources: [any LiveBoardRunSource] = [], noticeSources: [any LiveTrainNoticeSource] = []
    ) {
        self.statusSources = statusSources
        self.boardSources = boardSources
        self.statusTimeout = statusTimeout
        self.boardTimeout = boardTimeout
        self.runDetailSources = runDetailSources
        self.noticeSources = noticeSources
    }

    /// The iPhone's sources: ViaggiaTreno, RFI, Italo and transport.opendata.ch over `transport`
    /// (ephemeral `URLSession`s). Without `noticeRules` ViaggiaTreno's train notices are never asked.
    public static func standard(
        transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }, noticeRules: NoticeRules? = NoticeRules.standard
    ) -> LiveRailData {
        let viaggiaTreno = ViaggiaTrenoLiveSource(transport: transport, pauses: pauses, now: now, noticeRules: noticeRules)
        let italo = ItaloLiveSource(transport: transport, pauses: pauses, now: now)
        let rfi = RfiLiveSource(transport: transport, pauses: pauses, now: now)
        // Android asks it for every station: it answers only for Swiss and near-border ones, without a request elsewhere.
        let swiss = SwissStationBoardSource(transport: transport, pauses: pauses, now: now)
        return LiveRailData(
            statusSources: [viaggiaTreno, italo], boardSources: [rfi, italo, viaggiaTreno, swiss],
            runDetailSources: [viaggiaTreno], noticeSources: noticeRules == nil ? [] : [viaggiaTreno]
        )
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
        // `StationBoardAggregateService`: ten minutes back, six hours ahead, late trains from two hours
        // back, for the departures and the arrivals alike.
        let window = LiveBoardWindow(
            station: request.station, from: request.from.addingTimeInterval(-600),
            to: request.from.addingTimeInterval(6 * 3_600), lateSince: request.from.addingTimeInterval(-2 * 3_600),
            kind: request.kind
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
            sources: boards.map { $0.source }, unavailableSources: unavailable, warnings: warnings, fetchedAt: fetchedAt,
            kind: request.kind
        ))
    }

    /// The run of a board row, Android's `StationBoardRunDetailService.load`: each source in turn,
    /// within the board's time limit, the first that has it.
    public func boardRun(_ request: LiveBoardRunRequest) async -> LiveBoardRunResult {
        guard !runDetailSources.isEmpty else { return .unavailable(.notCovered) }
        let timeout = boardTimeout
        var reasons: [LiveUnavailableReason] = []
        for source in runDetailSources {
            let answer = await LiveTasks.withDeadline(seconds: timeout) { await source.boardRun(request) }
                ?? LiveSourceAnswer<LiveBoardRunDetails>.unavailable(.sourceUnavailable)
            if Task.isCancelled { return .unavailable(.cancelled) }
            switch answer {
            case let .answered(details, _):
                return .available(details)
            case let .unavailable(reason):
                reasons.append(reason)
            }
        }
        return .unavailable(Self.reason(reasons, answered: false))
    }

    /// Android's live route sources (`LocalOnlyRailDataPolicy.liveRouteSources`) for a search of today: the ViaggiaTreno
    /// route and the RFI route asked together, each within the live source time limit; the ViaggiaTreno route when it
    /// verified a run, else the RFI route when it did (Android's precedence, never the faster answer).
    public func liveRoute(_ request: LiveRouteRequest) async -> LiveRouteResult {
        let now = Date()
        guard LiveRailTime.isoDay(request.requested) == LiveRailTime.isoDay(now) else { return .unavailable(.notCovered) }
        let timeout = TimeInterval(SharedData.LiveRoute.liveSourceTimeoutSeconds)
        let vtBoard = boardSources.first { $0.source == .viaggiaTreno }
        let vtRuns = runDetailSources.first { $0.source == .viaggiaTreno }
        let rfi = boardSources.first { $0.source == .rfiStationBoard }
        async let viaggiaTreno: [LiveRouteRun]?? = LiveTasks.withDeadline(seconds: timeout) {
            guard let vtBoard, let vtRuns else { return nil }
            return await Self.viaggiaTrenoRoute(request, board: vtBoard, runs: vtRuns, now: now)
        }
        async let rfiRoute: [LiveRouteRun]?? = LiveTasks.withDeadline(seconds: timeout) {
            guard let rfi else { return nil }
            return await Self.rfiRoute(request, source: rfi, now: now)
        }
        if let runs = await viaggiaTreno ?? nil, runs.contains(where: \.verified) { return .available(runs, source: .viaggiaTreno) }
        if let runs = await rfiRoute ?? nil, runs.contains(where: \.verified) { return .available(runs, source: .rfiStationBoard) }
        return Task.isCancelled ? .unavailable(.cancelled) : .unavailable(.notFound)
    }

    /// Android's `RfiLiveRouteDataSource`: the RFI departures of the origin and arrivals of the destination, two hours
    /// back and eight ahead of now, the same train on both (`LiveRoute.rfiRuns`).
    static func rfiRoute(_ request: LiveRouteRequest, source: any LiveDepartureBoardSource, now: Date) async -> [LiveRouteRun]? {
        guard NetworkReliability.isStationCode(request.origin.railwayCode), NetworkReliability.isStationCode(request.destination.railwayCode)
        else { return nil }
        let from = now.addingTimeInterval(-LiveRoute.rfiLookBack)
        let to = now.addingTimeInterval(LiveRoute.rfiLookAhead)
        async let departures = source.departures(for: LiveBoardWindow(station: request.origin, from: from, to: to, lateSince: nil))
        async let arrivals = source.departures(for: LiveBoardWindow(station: request.destination, from: from, to: to, lateSince: nil, kind: .arrivals))
        guard case let .answered(leaving, _) = await departures, case let .answered(arriving, _) = await arrivals else { return nil }
        return LiveRoute.rfiRuns(
            departures: leaving, arrivals: arriving, origin: request.origin, destination: request.destination,
            requested: request.requested, now: now
        )
    }

    /// The route of `ViaggiaTrenoRealtimeAdapter`: the origin's ViaggiaTreno board from the time asked (and two hours
    /// before it when that is now), the trains still to leave, the planned numbers first on a busy board, the runs of
    /// the first twelve, each checked up to the destination (`LiveRoute.viaggiaTrenoRun`).
    static func viaggiaTrenoRoute(
        _ request: LiveRouteRequest, board: any LiveDepartureBoardSource, runs: any LiveBoardRunSource, now: Date
    ) async -> [LiveRouteRun]? {
        guard NetworkReliability.isStationCode(request.origin.railwayCode) else { return nil }
        let requested = request.requested
        let lateSince = requested <= now.addingTimeInterval(LiveRoute.viaggiaTrenoLookBackWithin)
            ? requested.addingTimeInterval(-LiveRoute.viaggiaTrenoLookBack) : nil
        let window = LiveBoardWindow(
            station: request.origin, from: requested, to: requested.addingTimeInterval(LiveRoute.viaggiaTrenoHorizon), lateSince: lateSince
        )
        guard case let .answered(rows, _) = await board.departures(for: window) else { return nil }
        let leaving = rows.filter { $0.viaggiaTrenoRun != nil && ($0.expected?.value ?? $0.scheduled) >= requested }
            .enumerated().sorted { first, second in
                first.element.scheduled != second.element.scheduled ? first.element.scheduled < second.element.scheduled : first.offset < second.offset
            }.map(\.element)
        let maximum = SharedData.LiveRoute.viaggiaTrenoMaximumRuns
        let hints = Set(request.plannedNumbers.map(NetworkReliability.numberKey))
        let prioritized = leaving.count > maximum
            ? leaving.filter { hints.contains($0.numberKey) } + leaving.filter { !hints.contains($0.numberKey) } : leaving
        let chosen = Array(prioritized.prefix(maximum))
        let detailTimeout = TimeInterval(SharedData.ViaggiaTreno.runDetailsTimeoutSeconds)
        let found = await withTaskGroup(of: LiveRouteRun?.self, returning: [LiveRouteRun].self) { group in
            for row in chosen {
                let detail = LiveBoardRunRequest(row, station: request.origin, kind: .departures)
                group.addTask {
                    let answer = await LiveTasks.withDeadline(seconds: detailTimeout) { await runs.boardRun(detail) }
                    guard case let .answered(details, _)? = answer else { return nil }
                    return LiveRoute.viaggiaTrenoRun(details.run, origin: request.origin, destination: request.destination, requested: requested)
                }
            }
            var collected: [LiveRouteRun] = []
            for await run in group { if let run { collected.append(run) } }
            return collected
        }
        var seen: Set<String> = []
        let unique = found.filter { seen.insert($0.id).inserted }.sorted { $0.scheduledDeparture < $1.scheduledDeparture }
        return unique.isEmpty ? nil : unique
    }

    /// The notices of a train, Android's `RailNoticesService.forTrain` for the per-train sources:
    /// asked together within the notices' time limit, each notice once, newest first; unavailable
    /// only when no source answered.
    public func trainNotices(_ request: LiveTrainNoticesRequest) async -> LiveTrainNoticesResult {
        guard !noticeSources.isEmpty else { return .unavailable(.notCovered) }
        let sources = noticeSources
        let timeout = NoticeTable.service.sourceTimeoutSeconds
        let answers = await LiveTasks.boundedMap(Array(sources.indices), maximumConcurrent: sources.count) { index in
            await LiveTasks.withDeadline(seconds: timeout) { await sources[index].trainNotices(request) }
                ?? LiveSourceAnswer<LiveTrainNotices>.unavailable(.sourceUnavailable)
        }
        if Task.isCancelled { return .unavailable(.cancelled) }
        var notices: [LiveTrainNotice] = []
        var seen: Set<String> = []
        var reasons: [LiveUnavailableReason] = []
        var stale = false
        var answered = false
        for answer in answers {
            switch answer {
            case let .answered(found, _):
                answered = true
                stale = stale || found.stale
                notices += found.notices.filter { seen.insert($0.id).inserted }
            case let .unavailable(reason):
                reasons.append(reason)
            }
        }
        guard answered else { return .unavailable(Self.reason(reasons, answered: false)) }
        let ordered = notices.enumerated().sorted { first, second in
            let a = first.element.publishedAt ?? Date(timeIntervalSince1970: 0)
            let b = second.element.publishedAt ?? Date(timeIntervalSince1970: 0)
            return a != b ? a > b : first.offset < second.offset
        }.map(\.element)
        return .available(LiveTrainNotices(notices: ordered, stale: stale))
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
/// same for every value. The scores and the operator bonus are the shared table
/// contracts/shared-data/v1/source-scores-v1.json (`SharedData.SourceScores`).
enum LiveSourcePolicy {
    enum Category {
        /// Expected times and running states.
        case trainRealtime
        /// Platforms, destinations and the row itself.
        case stationBoard

        /// The category's name in the shared table (Android's `RailInformationCategory`).
        var sharedName: String {
            switch self {
            case .trainRealtime: return "TRAIN_REALTIME"
            case .stationBoard: return "STATION_BOARD"
            }
        }
    }

    /// The source's score for `category`, plus the bonus of its operator rule when the hint names
    /// the train's operator as the source's own.
    static func score(_ category: Category, _ source: LiveSource, operatorHint: String?) -> Int {
        let base = SharedData.SourceScores.preferences.first {
            $0.category == category.sharedName && $0.source == source.rawValue
        }?.score ?? SharedData.SourceScores.defaultScore
        let hint = LiveText.boardKey(operatorHint ?? "")
        guard !hint.isEmpty,
              let rule = SharedData.SourceScores.operatorAffinity.first(where: { $0.sources.contains(source.rawValue) }),
              rule.operatorKeywords.contains(where: { hint.contains($0) })
        else { return base }
        return base + (rule.pointsByCategory[category.sharedName] ?? 0)
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
    /// Between a bundled timetable train and a live row, Android's `LiveRunMerge.RETIMING_WINDOW`:
    /// on one day at one station a train number is one train, so a run the day's timetable moved
    /// (works, a change after the pack was built) is still the bundled train; the next day's run,
    /// about 24 hours away, stays apart.
    public static let timetableTolerance = TimeInterval(SharedData.LiveRoute.retimingWindowMinutes * 60)

    /// A bundled timetable train to find on a live board: its number (any form), its scheduled
    /// departure and its operator as the timetable names it.
    public struct PlannedTrain: Equatable, Sendable {
        public let number: String
        public let scheduled: Date
        public let operatorName: String?

        public init(number: String, scheduled: Date, operatorName: String? = nil) {
            self.number = number
            self.scheduled = scheduled
            self.operatorName = operatorName
        }
    }

    /// The live row of the train numbered `number` (any form: "RE_11 - 2153", "FR 9558") leaving
    /// at `scheduled`: the same number key and operator family (`operatorFamily`; an unknown
    /// operator matches any), scheduled within `timetableTolerance`; the closer one when two are.
    /// Rows equally close are one train only when the board could not merge them (other sources,
    /// the same scheduled time: a destination spelt differently): the row of the board's best
    /// source then. Nil when none is close, or two equally close rows may be two trains.
    public static func departure(
        number: String, scheduled: Date, operatorName: String? = nil, in departures: [LiveDeparture]
    ) -> LiveDeparture? {
        let key = NetworkReliability.numberKey(number)
        let family = operatorFamily(operatorName)
        let near = departures.filter {
            $0.numberKey == key && abs($0.scheduled.timeIntervalSince(scheduled)) <= timetableTolerance
                && sameFamily(family, operatorFamily($0.operatorID))
        }.sorted { abs($0.scheduled.timeIntervalSince(scheduled)) < abs($1.scheduled.timeIntervalSince(scheduled)) }
        guard let closest = near.first else { return nil }
        let distance = abs(closest.scheduled.timeIntervalSince(scheduled))
        let tied = near.filter { abs($0.scheduled.timeIntervalSince(scheduled)) == distance }
        guard tied.count > 1 else { return closest }
        return bestRowOfOneTrain(tied)
    }

    /// The live row of each train of `planned` that has one (`departure(number:scheduled:operatorName:in:)`),
    /// by its index; nil entries are trains without a number of their own. A live row goes to one
    /// train only, the closest to it (none on a tie), as Android's `LiveRunMerge` pairs each run once.
    public static func departures(for planned: [PlannedTrain?], in departures: [LiveDeparture]) -> [Int: LiveDeparture] {
        var matched: [Int: LiveDeparture] = [:]
        var claims: [String: [Int]] = [:]
        for (index, entry) in planned.enumerated() {
            guard let train = entry,
                  let row = departure(number: train.number, scheduled: train.scheduled, operatorName: train.operatorName, in: departures)
            else { continue }
            matched[index] = row
            claims["\(row.id)|\(row.scheduled.timeIntervalSince1970)", default: []].append(index)
        }
        for indices in claims.values where indices.count > 1 {
            guard let row = matched[indices[0]] else { continue }
            let distance: (Int) -> TimeInterval = { index in
                planned[index].map { abs($0.scheduled.timeIntervalSince(row.scheduled)) } ?? .infinity
            }
            let best = indices.map(distance).min() ?? .infinity
            let closest = indices.filter { distance($0) == best }
            for index in indices where closest.count > 1 || index != closest[0] {
                matched[index] = nil
            }
        }
        return matched
    }

    /// Android's `LiveRunMerge.operatorFamily`: ViaggiaTreno publishes Trenitalia and Trenord runs
    /// under one identity, so the two are one family, Italo another; nil for any other or unknown
    /// operator, which matches every family.
    static func operatorFamily(_ value: String?) -> String? {
        guard let value else { return nil }
        let folded = value.lowercased()
        if folded.contains("trenitalia") || folded.contains("trenord") { return "trenitalia-trenord" }
        if folded.contains("italo") { return "italo" }
        return nil
    }

    private static func sameFamily(_ first: String?, _ second: String?) -> Bool {
        guard let first, let second else { return true }
        return first == second
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
        return departure(number: service.number, scheduled: scheduled, operatorName: service.operatorName, in: board.departures)
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
