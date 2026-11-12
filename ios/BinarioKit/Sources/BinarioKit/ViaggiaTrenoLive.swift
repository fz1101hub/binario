import Foundation

/// Android's `TrainNumberQuery`: exact public-number matching; category labels never turn it into
/// a substring search.
struct LiveTrainNumberQuery: Equatable, Sendable {
    let normalized: String
    /// The digits of the number without a known category prefix or leading zeros ("FR 09558" → "9558").
    let numeric: String?

    init(_ number: String) {
        // The run after a line ("S5 - 811A" asks 811A), as Android's `TrainNumberQuery`.
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        let run = trimmed.range(of: " - ", options: .backwards).map { String(trimmed[$0.upperBound...]) } ?? trimmed
        normalized = Self.normalize(run)
        numeric = Self.numeric(normalized)
    }

    var isValid: Bool {
        (1...32).contains(normalized.count)
            && normalized.allSatisfy { $0.isLetter || $0.isNumber }
            && normalized.contains { $0.isNumber }
    }

    func matches(_ published: String) -> Bool {
        let candidate: String
        if let separator = published.range(of: " - ", options: .backwards) {
            candidate = published[separator.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            candidate = published.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if normalized == Self.normalize(published) || normalized == Self.normalize(candidate) { return true }
        guard let numeric else { return false }
        return numeric == Self.numeric(Self.normalize(candidate))
    }

    private static func normalize(_ value: String) -> String {
        String(value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().filter { !$0.isWhitespace && $0 != "-" })
    }

    /// The categories Android accepts in front of a number, an explicit list: «VE545» is not train 545.
    private static let number = LiveRegex(
        "^(?:(?:FRECCIAROSSA|FRECCIARGENTO|FRECCIABIANCA|REGIONALE|ITALO|REG|RJX|ICE|ICN|TGV|TER|"
            + "REX|CJX|IRE|ECE|CAT|NJ|IR|ES|FR|FA|FB|AV|RV|RE|IC|EC|EN|RJ|R|S|D))?([0-9]+)$"
    )

    private static func numeric(_ value: String) -> String? {
        guard let groups = number.firstMatch(in: value), groups.count > 1, let digits = groups[1] else { return nil }
        let trimmed = String(digits.drop(while: { $0 == "0" }))
        return trimmed.isEmpty ? "0" : trimmed
    }
}

/// The ViaggiaTreno payloads, read as Android's `ViaggiaTrenoRealtimeAdapter` reads them. Pure
/// functions: every value is checked, a run that does not match the asked identity is refused.
enum ViaggiaTrenoParser {
    static let maximumAutocompleteLines = 200
    static let maximumReferences = 24
    static let maximumBoardRows = 2_000
    static let maximumStops = 400
    static let maximumNameLength = 96
    static let maximumPlatformLength = 12
    /// Delays beyond a day are not delays but errors of the payload.
    static let delayRange = -1_440...1_440
    /// How long a ViaggiaTreno value stays live (Android: 30 seconds).
    static let timeToLive: TimeInterval = 30
    /// `actualFermataType` of a cancelled stop (Android's `SUPPRESSED_STOP_TYPE`).
    static let suppressedStopType = 3
    /// `actualFermataType` of an extraordinary stop (Android's `EXTRAORDINARY_STOP_TYPE`).
    static let extraordinaryStopType = 2

    /// The runs of `cercaNumeroTrenoTrenoAutocomplete`: «12345 - VITERBO|12345-S08001-1760565600000».
    static func references(_ text: String, query: LiveTrainNumberQuery) -> [ViaggiaTrenoRunReference] {
        var result: [ViaggiaTrenoRunReference] = []
        var seen: Set<ViaggiaTrenoRunReference> = []
        for line in text.split(omittingEmptySubsequences: false, whereSeparator: { $0.isNewline }).prefix(maximumAutocompleteLines) {
            guard let bar = line.firstIndex(of: "|") else { continue }
            let identity = line[line.index(after: bar)...].trimmingCharacters(in: .whitespacesAndNewlines)
            let parts = identity.split(separator: "-", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 3, LiveText.isDigits(parts[0], count: 1...6), NetworkReliability.isStationCode(parts[1]),
                  LiveText.isDigits(parts[2], count: 10...16), let date = Int64(parts[2]), query.matches(parts[0]),
                  let reference = ViaggiaTrenoRunReference(originCode: parts[1], number: parts[0], departureDateMillis: date),
                  seen.insert(reference).inserted else { continue }
            result.append(reference)
            if result.count >= maximumReferences { break }
        }
        return result
    }

    /// The run of `andamentoTreno` as the number lookup builds it (Android `searchByNumber` with
    /// `parseBoardRun` at the first stop), or nil when it is not the asked run of `serviceDay`.
    static func run(
        _ root: LiveJSONObject, reference: ViaggiaTrenoRunReference, serviceDay: Date, fetchedAt: Date
    ) -> LiveRunStatus? {
        guard root.text("numeroTreno") == reference.number, root.text("idOrigine") == reference.originCode else { return nil }
        if let date = root.millis("dataPartenzaTreno"), date != reference.departureDateMillis { return nil }
        guard let calls = root.array("fermate"), !calls.isEmpty, calls.count <= maximumStops,
              let first = LiveJSONObject(calls.first), let last = LiveJSONObject(calls.last),
              let scheduledMillis = first.millis("partenza_teorica") ?? first.millis("programmata") else { return nil }
        let referenceDay = LiveRailTime.date(millis: reference.departureDateMillis)
        let scheduled = LiveRailTime.date(millis: scheduledMillis)
        guard plausible(scheduled, around: referenceDay) else { return nil }
        let end = (last.millis("arrivo_teorico") ?? last.millis("programmata")).map(LiveRailTime.date(millis:)) ?? scheduled
        // A run that starts after the asked day or ends before it is another day's run.
        guard LiveRailTime.days(from: scheduled, to: serviceDay) >= 0, LiveRailTime.days(from: serviceDay, to: end) >= 0,
              let firstCode = first.text("id") else { return nil }
        let rows = calls.compactMap { LiveJSONObject($0) }
        let selected = rows.filter {
            $0.text("id") == firstCode && ($0.millis("partenza_teorica") ?? $0.millis("programmata")) == scheduledMillis
        }
        guard selected.count == 1 else { return nil }

        let observedAt = root.millis("oraUltimoRilevamento").map(LiveRailTime.date(millis:))
        let observed = observedAt.flatMap { LiveRailTime.isRecent($0, fetched: fetchedAt) ? $0 : nil }
        let cancelled = root.int("provvedimento") == 1 || root.text("tipoTreno") == "ST"
        let delay = root.int("ritardo").flatMap { delayRange.contains($0) ? $0 : nil }
        let departed = !root.bool("nonPartito")
        // `parseBoardRun`: a recent observation and a published delay of a train that has left.
        let observedDelay = observed != nil && delay != nil && departed
        // `searchByNumber`: a cancellation is a verified state too.
        let verified = cancelled || observedDelay
        let stopsVerified = observedDelay && !cancelled

        var stops: [LiveStopStatus] = []
        for (index, row) in rows.enumerated() {
            if let parsed = stop(row, index: index, around: referenceDay, delay: delay, verified: stopsVerified) { stops.append(parsed) }
        }
        guard let firstStop = stops.first, let lastStop = stops.last else { return nil }

        let actual = time(first, "partenzaReale", near: scheduled)
        let delaySeconds = TimeInterval(delay ?? 0) * 60
        let expected = actual ?? (stopsVerified ? scheduled.addingTimeInterval(delaySeconds) : nil)
        let platform = LiveText.bounded(first.text("binarioEffettivoPartenzaDescrizione"), maximumLength: maximumPlatformLength)
        let scheduledPlatform = platform == nil
            ? nil : LiveText.bounded(first.text("binarioProgrammatoPartenzaDescrizione"), maximumLength: maximumPlatformLength)
        let brand = LiveText.bounded(root.text("compImpresa"), maximumLength: 64)
        let isTrenord = brand?.range(of: "trenord", options: .caseInsensitive) != nil
        let category = LiveText.bounded(root.text("categoria"), maximumLength: 16)?.uppercased() ?? ""
        let state: LiveRunState = cancelled ? .cancelled : (verified && (delay ?? 0) > 0 ? .delayed : .onTime)
        let cancellation: LiveCancellation = cancelled ? .full : (stops.contains(where: \.skipped) ? .partial : .none)
        let lastObservation = observedAt.flatMap { at -> LiveRunObservationPoint? in
            guard at <= fetchedAt.addingTimeInterval(LiveRailTime.futureToleranceSeconds),
                  at >= scheduled.addingTimeInterval(-86_400) else { return nil }
            return LiveRunObservationPoint(
                stationName: LiveText.bounded(root.text("stazioneUltimoRilevamento"), maximumLength: maximumNameLength),
                at: at, isRecent: observed != nil
            )
        }
        return LiveRunStatus(
            id: "viaggiatreno:\(reference.nativeID)", number: reference.number, category: category,
            operatorName: brand ?? (isTrenord ? "Trenord" : "Trenitalia"), operatorID: isTrenord ? "trenord" : "trenitalia",
            originCode: reference.originCode, originName: firstStop.stationName, destinationName: lastStop.stationName,
            scheduledDeparture: scheduled, expectedDeparture: expected ?? scheduled, platform: platform,
            scheduledPlatform: scheduledPlatform, state: state, cancellation: cancellation,
            delayMinutes: verified ? delay : nil, operationalStatusVerified: verified, lastObservation: lastObservation,
            stops: stops,
            observation: LiveObservation(
                source: .viaggiaTreno, fetchedAt: fetchedAt, observedAt: verified ? (observed ?? fetchedAt) : fetchedAt,
                expiresAt: fetchedAt.addingTimeInterval(timeToLive)
            ),
            viaggiaTrenoRun: reference
        )
    }

    /// One stop of `parseBoardRun`: the departure at the first stop, the arrival elsewhere.
    private static func stop(_ row: LiveJSONObject, index: Int, around referenceDay: Date, delay: Int?, verified: Bool) -> LiveStopStatus? {
        let scheduledKey = index == 0 ? "partenza_teorica" : "arrivo_teorico"
        guard let scheduledMillis = row.millis(scheduledKey) ?? row.millis("programmata"),
              let name = LiveText.bounded(row.text("stazione"), maximumLength: maximumNameLength) else { return nil }
        let scheduled = LiveRailTime.date(millis: scheduledMillis)
        guard plausible(scheduled, around: referenceDay) else { return nil }
        let actual = time(row, index == 0 ? "partenzaReale" : "arrivoReale", near: scheduled)
        let stopVerified = actual != nil || verified
        let delaySeconds = TimeInterval(stopVerified ? (delay ?? 0) : 0) * 60
        let expected = actual ?? scheduled.addingTimeInterval(delaySeconds)
        let delayMinutes = actual.map { LiveRailTime.wholeMinutes(from: scheduled, to: $0) } ?? (stopVerified ? (delay ?? 0) : 0)
        let departure = index == 0 ? nil : row.millis("partenza_teorica").map(LiveRailTime.date(millis:)).flatMap {
            plausible($0, around: referenceDay) ? $0 : nil
        }
        let platformKey = index == 0 ? "binarioEffettivoPartenzaDescrizione" : "binarioEffettivoArrivoDescrizione"
        let scheduledPlatformKey = index == 0 ? "binarioProgrammatoPartenzaDescrizione" : "binarioProgrammatoArrivoDescrizione"
        // ViaggiaTreno's actualFermataType as the punctuality pipeline and Android read it: 3 a
        // cancelled stop, 2 an extraordinary stop the timetable does not have (Trenitalia does not
        // document 2), so only 3 makes the run partly cancelled.
        let stopType = row.int("actualFermataType")
        return LiveStopStatus(
            stationCode: row.text("id").flatMap { NetworkReliability.isStationCode($0) ? $0 : nil },
            stationName: name, scheduled: scheduled, scheduledDeparture: departure, expected: expected, actual: actual,
            actualDeparture: index == 0 ? nil : time(row, "partenzaReale", near: scheduled),
            platform: LiveText.bounded(row.text(platformKey), maximumLength: maximumPlatformLength),
            scheduledPlatform: LiveText.bounded(row.text(scheduledPlatformKey), maximumLength: maximumPlatformLength),
            delayMinutes: delayMinutes, passed: actual != nil, skipped: stopType == suppressedStopType,
            operationalStatusVerified: stopVerified, added: stopType == extraordinaryStopType
        )
    }

    /// The departures of `partenze` as Android's `stationBoard` lists them, nil when unreadable.
    static func departures(_ text: String, window: LiveBoardWindow, fetchedAt: Date) -> [LiveDeparture]? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "null" { return [] }
        guard let array = LiveJSON.parse(trimmed) as? [Any] else { return nil }
        var result: [LiveDeparture] = []
        for item in array.prefix(maximumBoardRows) {
            guard let row = LiveJSONObject(item), let parsed = departure(row, window: window, fetchedAt: fetchedAt) else { continue }
            result.append(parsed)
        }
        return result
    }

    private static func departure(_ row: LiveJSONObject, window: LiveBoardWindow, fetchedAt: Date) -> LiveDeparture? {
        guard let scheduledMillis = row.millis("orarioPartenza"),
              let number = row.text("numeroTreno"), LiveText.isDigits(number, count: 1...6) else { return nil }
        let scheduled = LiveRailTime.date(millis: scheduledMillis)
        guard scheduled >= window.from.addingTimeInterval(-86_400), scheduled <= window.to.addingTimeInterval(86_400) else { return nil }
        let observed = row.millis("ultimoRilev").map(LiveRailTime.date(millis:)).flatMap {
            LiveRailTime.isRecent($0, fetched: fetchedAt) ? $0 : nil
        }
        let delay = row.int("ritardo").flatMap { delayRange.contains($0) ? $0 : nil }
        let cancelled = row.int("provvedimento") == 1
        let verified = cancelled || (observed != nil && delay != nil && !row.bool("nonPartito"))
        let delaySeconds = TimeInterval(delay ?? 0) * 60
        let expectedTime = verified ? scheduled.addingTimeInterval(delaySeconds) : nil
        guard window.keeps(scheduled: scheduled, expected: expectedTime) else { return nil }
        let observation = LiveObservation(
            source: .viaggiaTreno, fetchedAt: fetchedAt, observedAt: verified ? (observed ?? fetchedAt) : fetchedAt,
            expiresAt: fetchedAt.addingTimeInterval(timeToLive)
        )
        let platform = LiveText.bounded(row.text("binarioEffettivoPartenzaDescrizione"), maximumLength: maximumPlatformLength)
        let status: LiveDepartureStatus? = verified ? (cancelled ? .cancelled : ((delay ?? 0) > 0 ? .delayed : .onTime)) : nil
        let run = row.text("codOrigine").flatMap { origin -> ViaggiaTrenoRunReference? in
            guard let date = row.millis("dataPartenzaTreno") ?? row.millis("millisDataPartenza") else { return nil }
            return ViaggiaTrenoRunReference(originCode: origin, number: number, departureDateMillis: date)
        }
        return LiveDeparture(
            id: "viaggiatreno:\(number):\(LiveRailTime.isoDay(scheduled))", number: number, operatorID: boardOperatorID(row),
            category: LiveText.bounded(row.text("categoria"), maximumLength: 16) ?? "",
            destination: LiveText.bounded(row.text("destinazione"), maximumLength: maximumNameLength) ?? "",
            scheduled: scheduled, expected: expectedTime.map { LiveSourced($0, observation: observation) },
            platform: platform.map { LiveSourced($0, observation: observation) },
            scheduledPlatform: platform == nil
                ? nil : LiveText.bounded(row.text("binarioProgrammatoPartenzaDescrizione"), maximumLength: maximumPlatformLength),
            status: status.map { LiveSourced($0, observation: observation) }, sources: [.viaggiaTreno], viaggiaTrenoRun: run,
            observation: observation
        )
    }

    /// The operator a board row names itself (`compImpresa`, read as `run` reads it), else nil: the
    /// board lists Trenord's trains too, and an operator the row does not state must never keep it
    /// apart from RFI's row of the same train («TRENORD»).
    static func boardOperatorID(_ row: LiveJSONObject) -> String? {
        guard let brand = LiveText.bounded(row.text("compImpresa"), maximumLength: 64) else { return nil }
        if brand.range(of: "trenord", options: .caseInsensitive) != nil { return "trenord" }
        if brand.range(of: "trenitalia", options: .caseInsensitive) != nil { return "trenitalia" }
        return nil
    }

    /// A time of the payload within a day of `reference`, else nil (an unreadable or default value).
    private static func time(_ row: LiveJSONObject, _ key: String, near reference: Date) -> Date? {
        guard let millis = row.millis(key) else { return nil }
        let date = LiveRailTime.date(millis: millis)
        return abs(date.timeIntervalSince(reference)) <= 86_400 ? date : nil
    }

    /// A stop time between a day before and three days after the run's departure day.
    private static func plausible(_ date: Date, around referenceDay: Date) -> Bool {
        date >= referenceDay.addingTimeInterval(-86_400) && date <= referenceDay.addingTimeInterval(3 * 86_400)
    }

    /// "Fri Oct 16 2026 10:00:00 GMT+0200": Android's `EEE MMM dd yyyy HH:mm:ss 'GMT'xx` in Italian
    /// time, seconds dropped, then percent-encoded as `URLEncoder` does with "+" as "%20".
    static func boardTimePathComponent(_ date: Date) -> String {
        let calendar = LiveRailTime.calendar
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .weekday], from: date)
        let weekdays = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        let weekday = weekdays[min(max((parts.weekday ?? 1) - 1, 0), 6)]
        let month = months[min(max((parts.month ?? 1) - 1, 0), 11)]
        let offset = calendar.timeZone.secondsFromGMT(for: date)
        let sign = offset < 0 ? "-" : "+"
        let offsetHours = LiveText.padded(abs(offset) / 3_600, width: 2)
        let offsetMinutes = LiveText.padded((abs(offset) % 3_600) / 60, width: 2)
        let day = LiveText.padded(parts.day ?? 1, width: 2)
        let year = LiveText.padded(parts.year ?? 1970, width: 4)
        let clock = LiveText.padded(parts.hour ?? 0, width: 2) + ":" + LiveText.padded(parts.minute ?? 0, width: 2) + ":00"
        return formEncoded("\(weekday) \(month) \(day) \(year) \(clock) GMT\(sign)\(offsetHours)\(offsetMinutes)")
    }

    /// Java's `URLEncoder.encode(value, "UTF-8")` with spaces as "%20".
    static func formEncoded(_ value: String) -> String {
        let hex = Array("0123456789ABCDEF".utf8)
        var bytes: [UInt8] = []
        for byte in value.utf8 {
            switch byte {
            case 0x30...0x39, 0x41...0x5A, 0x61...0x7A, 0x2E, 0x2D, 0x2A, 0x5F:
                bytes.append(byte)
            default:
                bytes.append(0x25)
                bytes.append(hex[Int(byte >> 4)])
                bytes.append(hex[Int(byte & 0x0F)])
            }
        }
        return String(decoding: bytes, as: UTF8.self)
    }
}

/// ViaggiaTreno realtime, Android's `ViaggiaTrenoRealtimeAdapter`: train status by number
/// (autocomplete, then each run) and station departures. HTTPS to www.viaggiatreno.it, with the
/// one cleartext hop Android allows; one request per resource every 30 seconds.
public struct ViaggiaTrenoLiveSource: LiveTrainStatusSource, LiveDepartureBoardSource {
    public let source: LiveSource = .viaggiaTreno

    static let baseURL = "https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/"
    static let resources = ["partenze/", "arrivi/", "andamentoTreno/", "cercaNumeroTrenoTrenoAutocomplete/"]
    static let autocomplete = "cercaNumeroTrenoTrenoAutocomplete/"
    /// Android's `RatePolicy`: one request per resource every 30 seconds.
    static let minimumInterval: TimeInterval = 30
    /// All the run details of a number lookup (Android's `routeDetailsTimeoutMillis`).
    static let defaultDetailsTimeout: TimeInterval = 8
    /// The optional earlier board for late trains.
    static let defaultLookbackTimeout: TimeInterval = 2
    /// The main board and the optional lookback together, within `LiveRailData`'s 12-second board
    /// deadline with a second to spare: the lookback only gets the time the main board left, so a
    /// slow but complete main board is never thrown away for it.
    static let defaultBoardBudget: TimeInterval = 11
    /// Android's `DETAIL_PERMITS`.
    static let detailConcurrency = 3
    static let maximumBoardRows = 300

    let client: LiveSourceClient
    let gate: LiveSourceGate
    let now: @Sendable () -> Date
    let detailsTimeout: TimeInterval
    let lookbackTimeout: TimeInterval
    let boardBudget: TimeInterval

    init(
        client: LiveSourceClient, gate: LiveSourceGate, now: @escaping @Sendable () -> Date,
        detailsTimeout: TimeInterval = Self.defaultDetailsTimeout, lookbackTimeout: TimeInterval = Self.defaultLookbackTimeout,
        boardBudget: TimeInterval = Self.defaultBoardBudget
    ) {
        self.client = client
        self.gate = gate
        self.now = now
        self.detailsTimeout = detailsTimeout
        self.lookbackTimeout = lookbackTimeout
        self.boardBudget = boardBudget
    }

    public init(
        transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.init(
            client: LiveSourceClient(transport: transport, now: now),
            gate: LiveSourceGate(source: .viaggiaTreno, configuration: .init(minimumInterval: Self.minimumInterval), pauses: pauses, now: now),
            now: now
        )
    }

    static func policy(for path: String) -> LiveRequestPolicy {
        LiveRequestPolicy(
            accept: path.hasPrefix(autocomplete) ? "text/plain" : "application/json",
            userAgent: LiveSourceUserAgent.viaggiaTrenoRealtime, extraHeaders: ["Cache-Control": "no-cache"],
            requestTimeout: 12, totalTimeout: 12, maximumBytes: 2 * 1_048_576, maximumRequests: 4,
            allowsViaggiaTrenoCleartextHop: true, allowsViaggiaTrenoAlternateHost: true
        )
    }

    func body(_ path: String) async -> LiveFetchResult {
        guard Self.resources.contains(where: { path.hasPrefix($0) }), let url = URL(string: Self.baseURL + path) else {
            return .failure(.invalidRequest)
        }
        let client = self.client
        let policy = Self.policy(for: path)
        return await gate.fetch(path) { await client.get(url, policy: policy) }
    }

    public func runs(for request: LiveTrainStatusRequest) async -> LiveSourceAnswer<[LiveRunStatus]> {
        let query = LiveTrainNumberQuery(request.number)
        guard let number = query.numeric, LiveText.isDigits(number, count: 1...6) else { return .answered([], complete: true) }
        var references: [ViaggiaTrenoRunReference]
        if let known = request.viaggiaTrenoRun, known.number == number {
            references = [known]
        } else {
            switch await body(Self.autocomplete + number) {
            case let .failure(failure): return .unavailable(failure.unavailableReason)
            case let .body(body): references = ViaggiaTrenoParser.references(body.text, query: query)
            }
        }
        let serviceDay = request.serviceDate
        references = references.filter {
            let days = LiveRailTime.days(from: LiveRailTime.date(millis: $0.departureDateMillis), to: serviceDay)
            return (0...2).contains(days)
        }
        if let code = request.stationCode {
            let fromStation = references.filter { $0.originCode == code }
            if !fromStation.isEmpty { references = fromStation }
        }
        guard !references.isEmpty else { return .answered([], complete: true) }
        let collector = LiveCollector<LiveRunStatus>()
        let asked = references
        let finished = await LiveTasks.withDeadline(seconds: detailsTimeout) { [self] in
            _ = await LiveTasks.boundedMap(Array(asked.indices), maximumConcurrent: Self.detailConcurrency) { index in
                await self.detail(asked[index], order: index, serviceDay: serviceDay, into: collector)
            }
            return true
        } ?? false
        if Task.isCancelled { return .unavailable(.cancelled) }
        let gathered = collector.snapshot()
        if gathered.values.isEmpty, let failure = gathered.failures.first { return .unavailable(failure) }
        if gathered.values.isEmpty, !finished { return .unavailable(.sourceUnavailable) }
        return .answered(gathered.values, complete: finished && gathered.failures.isEmpty)
    }

    private func detail(
        _ reference: ViaggiaTrenoRunReference, order: Int, serviceDay: Date, into collector: LiveCollector<LiveRunStatus>
    ) async {
        switch await body("andamentoTreno/\(reference.originCode)/\(reference.number)/\(reference.departureDateMillis)") {
        case let .failure(failure):
            // A detail stopped by the deadline is no failure of the source: the answer is incomplete.
            if failure != .cancelled { collector.fail(failure.unavailableReason) }
        case let .body(body):
            guard let root = LiveJSONObject(LiveJSON.parse(body.text)) else { return collector.fail(.unreadable) }
            if let run = ViaggiaTrenoParser.run(root, reference: reference, serviceDay: serviceDay, fetchedAt: body.fetchedAt) {
                collector.append(run, order: order)
            }
        }
    }

    public func departures(for window: LiveBoardWindow) async -> LiveSourceAnswer<[LiveDeparture]> {
        guard let code = NetworkReliability.stationCode(StationIdentity(window.station)) else { return .unavailable(.notCovered) }
        // Elapsed time on a monotonic clock: `now` may be a test's fixed clock. DispatchTime, not
        // ProcessInfo.systemUptime: that one is a required-reason API (system boot time) the privacy
        // manifest would have to declare, or App Store Connect refuses the upload (ITMS-91053).
        let started = DispatchTime.now().uptimeNanoseconds
        let board = await body("partenze/\(code)/\(ViaggiaTrenoParser.boardTimePathComponent(window.from))")
        guard case let .body(main) = board else {
            if case let .failure(failure) = board { return .unavailable(failure.unavailableReason) }
            return .unavailable(.sourceUnavailable)
        }
        guard var rows = ViaggiaTrenoParser.departures(main.text, window: window, fetchedAt: main.fetchedAt) else {
            return .unavailable(.unreadable)
        }
        // From the window start, then (optional, short) from `lateSince` for the trains still late to
        // leave: an earlier board alone could hide the next trains of a busy station.
        let elapsed = TimeInterval(DispatchTime.now().uptimeNanoseconds &- started) / 1_000_000_000
        let remaining = boardBudget - elapsed
        if let since = window.lateSince, since < window.from, remaining > 0 {
            let path = "partenze/\(code)/\(ViaggiaTrenoParser.boardTimePathComponent(since))"
            let lookback = await LiveTasks.withDeadline(seconds: min(lookbackTimeout, remaining)) { [self] in await self.body(path) }
            if case let .body(earlier)? = lookback,
               let extra = ViaggiaTrenoParser.departures(earlier.text, window: window, fetchedAt: earlier.fetchedAt) {
                rows += extra
            }
        }
        var seen: Set<String> = []
        let unique = rows.filter { seen.insert("\($0.number):\(LiveRailTime.millis($0.scheduled))").inserted }
        if Task.isCancelled { return .unavailable(.cancelled) }
        return .answered(Array(unique.prefix(Self.maximumBoardRows)), complete: true)
    }
}
