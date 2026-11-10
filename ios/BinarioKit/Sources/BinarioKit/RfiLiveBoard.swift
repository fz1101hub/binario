import Foundation

/// One row of the RFI monitor, as Android's `RfiStationBoardAdapter.parseDepartures` reads it.
struct RfiMonitorRow: Equatable, Sendable {
    let number: String
    let destination: String
    /// "HH:mm" as the board prints it.
    let scheduled: String
    /// The delay the board publishes: the visible cell first, then its `aria-label`.
    let publishedDelay: Int?
    let cancelled: Bool
    /// The bare «RITARDO»: late, without minutes.
    let lateWithoutMinutes: Bool
    let platform: String?
    let carrier: String
    let category: String

    /// Android's `operationalStatusVerified` of the row.
    var verified: Bool { cancelled || publishedDelay != nil }

    /// Android's operator of the row («ITALO», «TRENORD», «FRECC…», «TRENITALIA» in carrier,
    /// category or number), as its `OperatorId`; nil for any other operator.
    var operatorID: String? {
        let all = "\(carrier) \(category) \(number)".uppercased()
        if all.contains("ITALO") || all.contains("NTV") { return "italo" }
        if all.contains("TRENORD") { return "trenord" }
        if all.contains("FRECC") || all.contains("TRENITALIA") { return "trenitalia" }
        return nil
    }
}

/// The public RFI pages: the monitor of a station and the station selector.
enum RfiMonitorParser {
    static let maximumRows = 400
    static let maximumCatalogueEntries = 10_000
    /// Android's `RfiStationBoardAdapter` cache and `SourceRef.expiresAt`: 45 seconds.
    static let timeToLive: TimeInterval = 45

    static func rows(_ html: String) -> [RfiMonitorRow] {
        let page = LiveHTML(html)
        let rows = page.elements(limit: maximumRows) { $0.name == "tr" && $0.attributes["name"]?.lowercased() == "treno" }
        return rows.compactMap { row(page, $0) }
    }

    private static func row(_ page: LiveHTML, _ row: LiveHTML.Element) -> RfiMonitorRow? {
        func cellText(_ id: String) -> String { page.element(id: id, in: row).map { page.text($0) } ?? "" }
        let number = cellText("RTreno")
        let destination = cellText("RStazione")
        let scheduled = normalizeTime(cellText("ROrario"))
        guard !number.isEmpty, number.count <= 16, !destination.isEmpty, destination.count <= 96, !scheduled.isEmpty else { return nil }
        let delayCell = page.element(id: "RRitardo", in: row)
        let delayLabel = delayCell.flatMap { page.tag($0).attributes["aria-label"] } ?? ""
        let delayText = delayCell.map { page.text($0) } ?? ""
        // The visible cell is what passengers see: some boards keep a generic or older aria-label.
        let publishedDelay = parseDelay(delayText) ?? parseDelay(delayLabel)
        let cancelled = LiveText.hasExplicitCancellationStatus(page.text(row))
        let lateWithoutMinutes = publishedDelay == nil && !cancelled && (isBareLateLabel(delayText) || isBareLateLabel(delayLabel))
        let platformText = cellText("RBinario")
        let platform = platformText.isEmpty || ["PF", "N/D", "-"].contains(platformText.uppercased()) || platformText.count > 12
            ? nil : platformText
        return RfiMonitorRow(
            number: number, destination: destination, scheduled: scheduled, publishedDelay: publishedDelay,
            cancelled: cancelled, lateWithoutMinutes: lateWithoutMinutes, platform: platform,
            carrier: imageLabel(page, "RVettore", in: row), category: imageLabel(page, "RCategoria", in: row)
        )
    }

    private static func imageLabel(_ page: LiveHTML, _ id: String, in row: LiveHTML.Element) -> String {
        guard let cell = page.element(id: id, in: row), let image = page.first(in: cell, where: { $0.name == "img" }) else { return "" }
        return String(LiveText.compact(page.tag(image).attributes["alt"] ?? "").prefix(64))
    }

    private static let clockTime = LiveRegex(#"\b([01]?\d|2[0-3]):[0-5]\d\b"#)
    private static let delayPattern = LiveRegex(#"^(?:RITARDO(?:\s+DI)?\s*[:=]?\s*)?([+-]?\d{1,3})\s*(?:MIN(?:UTI)?\.?|['′])?$"#)

    /// The first clock time of the cell, "" without one.
    static func normalizeTime(_ value: String) -> String {
        clockTime.firstMatch(in: value)?.first.flatMap { $0 } ?? ""
    }

    /// «12 minuti», «+5», «RITARDO 7'», «Nessuno» or «In orario» (0); nil for anything else, a
    /// quoted «"Nessuno"» placeholder included.
    static func parseDelay(_ value: String) -> Int? {
        let normalized = LiveText.compact(value.uppercased())
        if normalized == "NESSUNO" || normalized == "IN ORARIO" { return 0 }
        let numeric = LiveText.removingSurrounding(LiveText.removingSurrounding(normalized, "\""), "'")
        guard let groups = delayPattern.firstMatch(in: numeric), groups.count > 1, let digits = groups[1] else { return nil }
        return Int(digits.hasPrefix("+") ? String(digits.dropFirst()) : digits)
    }

    /// «RITARDO» alone, in any case, with spaces or quotes around it.
    static func isBareLateLabel(_ value: String) -> Bool {
        let normalized = LiveText.compact(value.uppercased())
        let unquoted = LiveText.removingSurrounding(LiveText.removingSurrounding(normalized, "\""), "'")
        return unquoted.trimmingCharacters(in: .whitespaces) == "RITARDO"
    }

    /// The station selector of the monitor (`select#ElencoLocalita`): place IDs by station key.
    static func catalogue(_ html: String) -> [String: Set<Int>] {
        let page = LiveHTML(html)
        var names: [String: Set<Int>] = [:]
        let selects = page.elements { $0.name == "select" && $0.attributes["id"] == "ElencoLocalita" }
        for select in selects {
            for option in page.elements(in: select, limit: maximumCatalogueEntries, where: { $0.name == "option" }) {
                guard let value = page.tag(option).attributes["value"], LiveText.isDigits(value, count: 1...9),
                      let id = Int(value), id > 0 else { continue }
                let key = LiveText.catalogueStationKey(page.text(option), sanPrefix: true)
                guard !key.isEmpty else { continue }
                names[key, default: []].insert(id)
            }
        }
        return names
    }

    /// The board rows as Android's `LegacyStationBoardInterop.build` lists them: the clock time on
    /// the day that falls in the window, an expected time only for a published delay, a status for a
    /// published state or a bare «RITARDO».
    static func departures(_ rows: [RfiMonitorRow], window: LiveBoardWindow, fetchedAt: Date) -> [LiveDeparture] {
        let observation = LiveObservation(
            source: .rfiStationBoard, fetchedAt: fetchedAt, observedAt: fetchedAt, expiresAt: fetchedAt.addingTimeInterval(timeToLive)
        )
        return rows.compactMap { row -> LiveDeparture? in
            guard let scheduled = LiveBoardTime.resolve(row.scheduled, from: window.lateSince ?? window.from, to: window.to) else {
                return nil
            }
            let delay = row.publishedDelay ?? 0
            let timingVerified = row.publishedDelay != nil && !row.cancelled
            let delaySeconds = TimeInterval(delay) * 60
            let expected = timingVerified ? scheduled.addingTimeInterval(delaySeconds) : nil
            guard window.keeps(scheduled: scheduled, expected: expected) else { return nil }
            let status: LiveDepartureStatus?
            if row.verified {
                status = row.cancelled ? .cancelled : (delay > 0 ? .delayed : .onTime)
            } else {
                status = row.lateWithoutMinutes ? .delayed : nil
            }
            let category = row.category.hasPrefix("Categoria ") ? String(row.category.dropFirst("Categoria ".count)) : row.category
            return LiveDeparture(
                id: "rfi-station-board:\(row.number):\(LiveRailTime.isoDay(scheduled))", number: row.number,
                operatorID: row.operatorID, category: category, destination: row.destination, scheduled: scheduled,
                expected: expected.map { LiveSourced($0, observation: observation) },
                platform: row.platform.map { LiveSourced($0, observation: observation) }, scheduledPlatform: nil,
                status: status.map { LiveSourced($0, observation: observation) }, sources: [.rfiStationBoard],
                viaggiaTrenoRun: nil, observation: observation
            )
        }
    }
}

/// Clock times of a board on the right day (Android's `LegacyStationBoardInterop.resolveBoardDateTime`).
enum LiveBoardTime {
    /// "HH:mm" on the day before, of or after `from` that falls in `from`…`to`, else the one nearest to `from`.
    static func resolve(_ value: String, from: Date, to: Date) -> Date? {
        guard let clock = LiveRailTime.clock(value),
              let base = LiveRailTime.date(onDayOf: from, hour: clock.hour, minute: clock.minute) else { return nil }
        let candidates = [-1, 0, 1].compactMap { offset -> Date? in
            guard let day = LiveRailTime.addingDays(offset, to: LiveRailTime.startOfDay(from)) else { return nil }
            return LiveRailTime.date(onDayOf: day, hour: clock.hour, minute: clock.minute)
        }
        if let inside = candidates.first(where: { $0 >= from && $0 <= to }) { return inside }
        return candidates.min { abs($0.timeIntervalSince(from)) < abs($1.timeIntervalSince(from)) } ?? base
    }

    /// Android's `resolveExpectedDateTime`: the clock time on the scheduled day, a day later or
    /// earlier when it is more than 12 hours before or after the scheduled time.
    static func expected(_ value: String, scheduled: Date) -> Date? {
        guard let clock = LiveRailTime.clock(value),
              var expected = LiveRailTime.date(onDayOf: scheduled, hour: clock.hour, minute: clock.minute) else { return nil }
        if expected < scheduled.addingTimeInterval(-12 * 3_600) { expected = LiveRailTime.addingDays(1, to: expected) ?? expected }
        if expected > scheduled.addingTimeInterval(12 * 3_600) { expected = LiveRailTime.addingDays(-1, to: expected) ?? expected }
        return expected
    }
}

/// The RFI monitor's place IDs, Android's `RfiStationIds` (the names it knows) and
/// `RfiStationCatalog` (the official selector, read once a day; a minute after a failure).
final class RfiPlaceCatalogue: @unchecked Sendable {
    /// Android's `RfiStationIds.byName`. Its `byLegacyId` keys are Android's own station identifiers,
    /// which the iPhone catalogue does not use.
    static let knownPlaces: [String: Int] = {
        let names: [(String, Int)] = [
            ("Roma Termini", 2416), ("Roma Tiburtina", 2385), ("Roma Ostiense", 2379), ("Roma Trastevere", 2387),
            ("Roma San Pietro", 2383), ("Roma S. Pietro", 2383), ("Roma Tuscolana", 2388), ("Fiumicino Aeroporto", 1327),
            ("Ciampino", 1106), ("Civitavecchia", 1123), ("Frascati", 1370), ("Velletri", 3006), ("Orte", 1959),
            ("Latina", 1539), ("Frosinone", 1377), ("Cassino", 955), ("Napoli Centrale", 1888), ("Bologna Centrale", 683),
            ("Milano Centrale", 1728), ("Genova Piazza Principe", 257), ("Genova P. Principe", 257),
            ("Torino Porta Nuova", 2876), ("Bari Centrale", 595), ("Palermo Centrale", 2018), ("Cagliari", 787),
        ]
        var places: [String: Int] = [:]
        for (name, id) in names { places[LiveText.legacyStationKey(name)] = id }
        return places
    }()

    static let refreshInterval: TimeInterval = 24 * 3_600
    static let retryInterval: TimeInterval = 60

    private let lock = NSLock()
    private var names: [String: Set<Int>] = [:]
    private var refreshAfter = Date.distantPast

    /// The place ID Android knows for the station name, else the single one the catalogue gives
    /// the name and the aliases together (an ambiguous or unknown name has none).
    func placeID(name: String, aliases: [String], now: Date, load: @Sendable () async -> LiveFetchResult) async -> Int? {
        if let known = Self.knownPlaces[LiveText.legacyStationKey(name)] { return known }
        let stationNames = [name] + aliases
        if needsRefresh(at: now) {
            switch await load() {
            case let .body(body):
                update(RfiMonitorParser.catalogue(body.text), at: now)
            case .failure(.cancelled):
                return nil
            case .failure:
                update([:], at: now)
            }
        }
        return lookup(stationNames)
    }

    private func needsRefresh(at now: Date) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return now >= refreshAfter
    }

    private func update(_ parsed: [String: Set<Int>], at now: Date) {
        lock.lock()
        if parsed.isEmpty {
            refreshAfter = now.addingTimeInterval(Self.retryInterval)
        } else {
            names = parsed
            refreshAfter = now.addingTimeInterval(Self.refreshInterval)
        }
        lock.unlock()
    }

    private func lookup(_ stationNames: [String]) -> Int? {
        lock.lock()
        defer { lock.unlock() }
        var found: Set<Int> = []
        for name in stationNames {
            found.formUnion(names[LiveText.catalogueStationKey(name, sanPrefix: true)] ?? [])
        }
        return found.count == 1 ? found.first : nil
    }
}

/// The RFI «Arrivi e partenze» monitor, Android's `RfiStationBoardAdapter`: the departures of a
/// station in the official selector, HTTPS to iechub.rfi.it only.
public struct RfiLiveSource: LiveDepartureBoardSource {
    public let source: LiveSource = .rfiStationBoard

    static let catalogueURL = "https://iechub.rfi.it/ArriviPartenze"
    static let monitorURL = "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor"
    /// Android's `RatePolicy`: one request per board every 20 seconds.
    static let minimumInterval: TimeInterval = 20
    static let policy = LiveRequestPolicy(
        accept: "text/html,application/xhtml+xml", extraHeaders: ["Cache-Control": "no-cache"],
        requestTimeout: 10, totalTimeout: 10, maximumBytes: 2 * 1_048_576
    )

    let client: LiveSourceClient
    let gate: LiveSourceGate
    let catalogue: RfiPlaceCatalogue

    init(client: LiveSourceClient, gate: LiveSourceGate, catalogue: RfiPlaceCatalogue = RfiPlaceCatalogue()) {
        self.client = client
        self.gate = gate
        self.catalogue = catalogue
    }

    public init(
        transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.init(
            client: LiveSourceClient(transport: transport, now: now),
            gate: LiveSourceGate(source: .rfiStationBoard, configuration: .init(minimumInterval: Self.minimumInterval), pauses: pauses, now: now)
        )
    }

    func body(_ address: String) async -> LiveFetchResult {
        guard let url = URL(string: address) else { return .failure(.invalidRequest) }
        let client = self.client
        return await gate.fetch(address) { await client.get(url, policy: Self.policy) }
    }

    public func departures(for window: LiveBoardWindow) async -> LiveSourceAnswer<[LiveDeparture]> {
        let gate = self.gate
        let client = self.client
        guard let placeID = await catalogue.placeID(name: window.station.name, aliases: window.station.aliases, now: client.now(), load: {
            guard let url = URL(string: Self.catalogueURL) else { return .failure(.invalidRequest) }
            return await gate.fetch(Self.catalogueURL) { await client.get(url, policy: Self.policy) }
        }) else {
            if let until = gate.pausedUntil() { return .unavailable(.paused(until: until)) }
            return .unavailable(Task.isCancelled ? .cancelled : .notCovered)
        }
        switch await body("\(Self.monitorURL)?placeId=\(placeID)&arrivals=False") {
        case let .failure(failure):
            return .unavailable(failure.unavailableReason)
        case let .body(body):
            let rows = RfiMonitorParser.rows(body.text)
            return .answered(RfiMonitorParser.departures(rows, window: window, fetchedAt: body.fetchedAt), complete: true)
        }
    }
}
