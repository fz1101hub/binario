import Foundation

// RFI's public programmed timetable («Quadro orario», prm.rfi.it) on the iPhone, as Android reads it
// (`RfiProgrammedTimetableAdapter`, `RfiSupplementalHubTimetableAdapter`, `RfiProgrammedStationBoardAdapter`): the
// whole-day departures (or arrivals) page of a verified hub, its HTML table read row by row, the periodicity of each
// train checked against the Italian holidays. The iPhone asks it only where the bundled timetable gives no train
// (`TimetableFallbacks`). The hubs, address and limits are the shared table rfi-programmed-v1.json; the cases both
// platforms read are contracts/shared-rules/v1/rfi-programmed-v1.json.

/// Android's `RailOperator` of a programmed row.
public enum RfiProgrammedOperator: String, Equatable, Sendable {
    case italo = "ITALO"
    case trenord = "TRENORD"
    case trenitaliaHighSpeed = "TRENITALIA_AV"
    case trenitaliaLongDistance = "TRENITALIA_LONG_DISTANCE"
    case trenitaliaRegional = "TRENITALIA_REGIONALE"
    case other = "OTHER"

    /// The operator a row shows: the brand, nil for one RFI does not name.
    public var displayName: String? {
        switch self {
        case .italo: return "Italo"
        case .trenord: return "Trenord"
        case .trenitaliaHighSpeed, .trenitaliaLongDistance, .trenitaliaRegional: return "Trenitalia"
        case .other: return nil
        }
    }
}

/// Android's `TrainCategory` of a programmed row.
public enum RfiProgrammedCategory: String, Equatable, Sendable {
    case frecce = "FRECCE"
    case italo = "AV_ITALO"
    case intercity = "INTERCITY"
    case regionalExpress = "REGIONALE_VELOCE"
    case regional = "REGIONALE"
    case other = "OTHER"

    /// The short category a row shows next to the number.
    public var label: String {
        switch self {
        case .frecce: return "FR"
        case .italo: return "ITALO"
        case .intercity: return "IC"
        case .regionalExpress: return "RV"
        case .regional: return "R"
        case .other: return "RFI"
        }
    }
}

/// A train of a programmed page: its number and time at the page's station, the stops RFI lists with their times
/// (the page's station first), its terminus, operator, category and periodicity.
public struct RfiProgrammedRow: Equatable, Sendable {
    public struct Stop: Equatable, Sendable {
        public let station: String
        /// «HH:mm», or «—» for a terminus without a time.
        public let time: String
    }

    public let number: String
    public let scheduled: String
    public let destination: String
    public let stops: [Stop]
    public let operatorKind: RfiProgrammedOperator
    public let category: RfiProgrammedCategory
    public let periodicity: String
    /// Arrival pages: the days between the train leaving its first listed stop and reaching the page's station.
    public let boardDayOffset: Int
}

/// A read page: the period RFI says it is valid for, and its trains.
public struct RfiProgrammedPage: Equatable, Sendable {
    public let validFrom: RailDay?
    public let validUntil: RailDay?
    public let rows: [RfiProgrammedRow]

    func isValid(on day: RailDay) -> Bool {
        guard let validFrom, let validUntil else { return true }
        return validFrom <= day && day <= validUntil
    }
}

/// The verified hubs of the shared table: Android's `RfiProgrammedStationIds` and `SupplementalRfiProgrammedStationIds`
/// (its legacy station identifiers aside, which the iPhone has never had).
public enum RfiProgrammedStations {
    /// The timetable identifier of a main hub: by railway code, else by its name or an alias.
    public static func mainTimetableId(_ station: Station) -> Int? {
        timetableId(station, hubs: SharedData.RfiProgrammed.hubs)
    }

    /// The timetable identifier of a supplemental hub.
    public static func supplementalTimetableId(_ station: Station) -> Int? {
        timetableId(station, hubs: SharedData.RfiProgrammed.supplementalHubs)
    }

    private static func timetableId(_ station: Station, hubs: [SharedData.RfiProgrammed.Hub]) -> Int? {
        if let code = station.railwayCode, let hub = hubs.first(where: { $0.railwayCode == code }) { return hub.timetableId }
        for name in [station.name] + station.aliases {
            let wanted = key(name)
            if let hub = hubs.first(where: { $0.names.contains { key($0) == wanted } }) { return hub.timetableId }
        }
        return nil
    }

    /// Android's `key`: upper case, «S. » read as «SAN », letters and digits only.
    static func key(_ value: String) -> String {
        let upper = value.uppercased().replacingOccurrences(of: "S. ", with: "SAN ")
        return String(String.UnicodeScalarView(upper.unicodeScalars.filter { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }))
    }

    /// The page of a whole day's departures or arrivals (`urlTemplate`).
    public static func url(timetableId: Int, arrivals: Bool) -> URL? {
        let board = arrivals ? SharedData.RfiProgrammed.arrivalsBoard : SharedData.RfiProgrammed.departuresBoard
        return URL(string: SharedData.RfiProgrammed.urlTemplate
            .replacingOccurrences(of: "<board>", with: board)
            .replacingOccurrences(of: "<id>", with: String(timetableId)))
    }
}

/// Android's `parseTimetable`, `parseRow`, `parseStops` and `parseValidity`, as pure functions.
public enum RfiProgrammedParser {
    private static let numberPattern = LiveRegex(#"\b(\d{2,5})\b"#)
    private static let timePattern = LiveRegex(#"\b([01]?\d|2[0-3])[.:][0-5]\d\b"#)
    private static let stopPattern = LiveRegex(#"([A-Za-zÀ-ÿ0-9 .'/’–-]{2,80}?)\s*\((\d{1,2}[.:]\d{2})\)"#)
    private static let validityPattern = LiveRegex(
        #"(\d{1,2})\s+([A-Za-zÀ-ÿ]+)\s+(20\d{2})\s*[-–]\s*(\d{1,2})\s+([A-Za-zÀ-ÿ]+)\s+(20\d{2})"#, caseInsensitive: true
    )
    private static let italoWord = LiveRegex(#"\bITA\b"#)
    private static let intercityWord = LiveRegex(#"\bICN?\b"#)
    private static let regionalWord = LiveRegex(#"\bRV?\b"#)
    private static let regionalExpressWord = LiveRegex(#"\bRV\b"#)
    private static let plainRegionalWord = LiveRegex(#"\bR\b"#)
    private static let missingDestination = "Destinazione non indicata"

    /// The trains of `html`, read for `origin` (the page's station). An arrivals page lists a train from `origin` to
    /// `destinationName` and is read only when its headers are those of an arrivals board.
    public static func parse(_ html: String, origin: Station, destinationName: String = "", arrivals: Bool = false) -> RfiProgrammedPage {
        let document = RfiHTML.parse(html)
        let validity = parseValidity(document.textContent)
        var rows: [RfiProgrammedRow] = []
        for table in document.descendants(named: ["table"]) {
            let tableRows = table.descendants(named: ["tr"])
            guard !tableRows.isEmpty else { continue }
            let marker = arrivals ? "FERMATE PRECEDENTI" : "FERMATE SUCCESSIVE"
            let headerRow = tableRows.first { !$0.descendants(named: ["th"]).isEmpty }
                ?? tableRows.first { $0.textContent.uppercased().contains(marker) }
            let headers = headerRow?.descendants(named: ["th", "td"]).map { normalizeHeader(elementText($0)) } ?? []
            let indexes = HeaderIndexes(headers)
            // An arrivals reading never takes a departures board or an error page for arrivals.
            if arrivals {
                guard let previous = indexes.previousStops, headers[previous].contains("ORARIO DI PARTENZA"),
                      headers.contains(where: { $0.hasPrefix("ORARIO DI ARRIVO") }) else { continue }
            }
            for row in tableRows {
                if let parsed = parseRow(row, indexes, origin: origin, destinationName: destinationName, arrivals: arrivals) {
                    rows.append(parsed)
                }
            }
        }
        var seen = Set<String>()
        rows = rows.filter { seen.insert("\($0.number):\($0.scheduled):\($0.destination)").inserted }
        return RfiProgrammedPage(validFrom: validity?.0, validUntil: validity?.1, rows: rows)
    }

    private struct HeaderIndexes {
        let time: Int?
        let train: Int?
        let destination: Int?
        let stops: Int?
        let previousStops: Int?
        let carrier: Int?
        let periodicity: Int?

        init(_ headers: [String]) {
            func index(_ terms: String...) -> Int? {
                headers.firstIndex { header in terms.contains { header.contains($0) } }
            }
            time = index("ORARIO", "ORA")
            train = index("TRENO")
            destination = index("DESTINAZIONE", "STAZIONE DI ARRIVO", "LOCALITA DI ARRIVO", "LOCALITÀ DI ARRIVO")
            stops = index("FERMATE SUCCESSIVE")
            previousStops = index("FERMATE PRECEDENTI")
            carrier = index("IMPRESA DI TRASPORTO", "IMPRESA")
            periodicity = index("PERIODICITA", "PERIODICITÀ")
        }
    }

    private static func parseRow(
        _ row: RfiHTMLNode, _ indexes: HeaderIndexes, origin: Station, destinationName: String, arrivals: Bool
    ) -> RfiProgrammedRow? {
        let cells = row.descendants(named: ["td"])
        guard cells.count >= 2 else { return nil }
        func cell(_ index: Int?, _ word: String) -> RfiHTMLNode? {
            if let index, index >= 0, index < cells.count { return cells[index] }
            return row.firstElement(attributeContains: [("id", word), ("class", word), ("headers", word)])
        }
        guard let trainCell = cell(indexes.train, "treno")
                ?? cells.first(where: { numberPattern.contains(elementText($0)) }) else { return nil }
        let trainText = elementText(trainCell)
        guard let number = numberPattern.firstMatch(in: trainText)?[1] ?? nil else { return nil }

        var boardTime = normalizeTime(elementText(cell(indexes.time, "orario")))
        if boardTime.isEmpty, !arrivals {
            boardTime = cells.lazy.map { normalizeTime(elementText($0)) }.first { !$0.isEmpty } ?? ""
        }
        guard !boardTime.isEmpty else { return nil }

        var arrivalStops: [RfiProgrammedRow.Stop] = []
        if arrivals {
            guard let previousIndex = indexes.previousStops else { return nil }
            let previousCell: RfiHTMLNode? = previousIndex < cells.count ? cells[previousIndex] : nil
            let previous = parseStops(elementText(previousCell))
            guard let originIndex = previous.firstIndex(where: { names(origin, $0.station) }) else { return nil }
            arrivalStops = Array(previous[originIndex...]) + [RfiProgrammedRow.Stop(station: destinationName, time: boardTime)]
        }
        let scheduled = arrivalStops.first?.time ?? boardTime

        let destinationText = elementText(cell(indexes.destination, "destin"))
        let destinationPart = destinationText.split(separator: "(", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let trimmedDestination = kotlinTrim(destinationPart)
        let destination = trimmedDestination.isEmpty ? missingDestination : trimmedDestination
        let successiveStops = parseStops(elementText(cell(indexes.stops, "fermate")))
        let lastSuccessive = successiveStops.last?.station
        let finalDestination: String
        if let last = arrivalStops.last?.station {
            finalDestination = last
        } else if let lastSuccessive, !kotlinTrim(lastSuccessive).isEmpty {
            finalDestination = lastSuccessive
        } else if destination != missingDestination {
            finalDestination = destination
        } else {
            return nil
        }

        let periodicity = normalizeHeader(elementText(cell(indexes.periodicity, "period")))
        let carrier = elementText(cell(indexes.carrier, "impresa"))
        let categoryText = "\(trainText) \(carrier)"
        let operatorKind = operatorOf(categoryText)
        let category = categoryOf(categoryText, operatorKind: operatorKind)

        var stops = [RfiProgrammedRow.Stop(station: origin.name, time: scheduled)]
        if arrivals {
            stops += arrivalStops.dropFirst()
        } else if !successiveStops.isEmpty {
            stops += successiveStops
        } else if !names(origin, finalDestination) {
            stops.append(RfiProgrammedRow.Stop(station: finalDestination, time: "—"))
        }
        var offset = 0
        if arrivals {
            for (first, second) in zip(arrivalStops, arrivalStops.dropFirst()) where minutes(second.time) < minutes(first.time) {
                offset += 1
            }
        }
        return RfiProgrammedRow(
            number: number, scheduled: scheduled, destination: finalDestination, stops: stops, operatorKind: operatorKind,
            category: category, periodicity: periodicity, boardDayOffset: offset
        )
    }

    /// Android's `parseStops`: «Station (hh.mm)» runs, each station cut after its last «;» or «|» and trimmed.
    static func parseStops(_ value: String) -> [RfiProgrammedRow.Stop] {
        let text = compact(value)
        guard !text.isEmpty else { return [] }
        return stopPattern.allMatches(in: text, limit: 1_000).compactMap { groups -> RfiProgrammedRow.Stop? in
            guard groups.count > 2, let rawStation = groups[1], let rawTime = groups[2] else { return nil }
            var station = rawStation
            if let semicolon = station.lastIndex(of: ";") { station = String(station[station.index(after: semicolon)...]) }
            if let bar = station.lastIndex(of: "|") { station = String(station[station.index(after: bar)...]) }
            station = station.trimmingCharacters(in: CharacterSet(charactersIn: " -–,;"))
            let time = padded(rawTime.replacingOccurrences(of: ".", with: ":"))
            guard !kotlinTrim(station).isEmpty, minutes(time) != Int.max else { return nil }
            return RfiProgrammedRow.Stop(station: station, time: time)
        }
    }

    /// Android's `parseValidity`: «14 Giugno 2026 - 12 Dicembre 2026».
    static func parseValidity(_ text: String) -> (RailDay, RailDay)? {
        guard let groups = validityPattern.firstMatch(in: text), groups.count == 7,
              let startDay = groups[1].flatMap({ Int($0) }), let startMonth = groups[2].flatMap(italianMonth),
              let startYear = groups[3].flatMap({ Int($0) }), let endDay = groups[4].flatMap({ Int($0) }),
              let endMonth = groups[5].flatMap(italianMonth), let endYear = groups[6].flatMap({ Int($0) }),
              let start = RailDay(year: startYear, month: startMonth, day: startDay),
              let end = RailDay(year: endYear, month: endMonth, day: endDay) else { return nil }
        return (start, end)
    }

    private static func italianMonth(_ value: String) -> Int? {
        let prefix = String(kotlinTrim(value).lowercased().prefix(3))
        let months = ["gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic"]
        return months.firstIndex(of: prefix).map { $0 + 1 }
    }

    static func operatorOf(_ value: String) -> RfiProgrammedOperator {
        let all = value.uppercased()
        if all.contains("ITALO") || all.contains("NTV") || italoWord.contains(all) { return .italo }
        if all.contains("TRENORD") { return .trenord }
        if all.contains("FRECC") { return .trenitaliaHighSpeed }
        if all.contains("INTERCITY") || intercityWord.contains(all) { return .trenitaliaLongDistance }
        if all.contains("REGIONALE") || regionalWord.contains(all) { return .trenitaliaRegional }
        return .other
    }

    static func categoryOf(_ value: String, operatorKind: RfiProgrammedOperator) -> RfiProgrammedCategory {
        let all = value.uppercased()
        if operatorKind == .italo { return .italo }
        if all.contains("FRECC") { return .frecce }
        if all.contains("INTERCITY") || intercityWord.contains(all) { return .intercity }
        if all.contains("REGIONALE VELOCE") || regionalExpressWord.contains(all) { return .regionalExpress }
        if all.contains("REGIONALE") || plainRegionalWord.contains(all) { return .regional }
        return .other
    }

    /// Android's `elementText`: the images' alternative texts (not those naming a station or accessibility), then
    /// the text.
    private static func elementText(_ element: RfiHTMLNode?) -> String {
        guard let element else { return "" }
        let labels = element.imageAlternatives.filter { alt in
            !kotlinTrim(alt).isEmpty && !alt.lowercased().hasPrefix("stazione") && !alt.lowercased().contains("accessibil")
        }
        return compact((labels + [element.textContent]).joined(separator: " "))
    }

    /// Android's `compactText`: no-break spaces as spaces, whitespace runs as one space, trimmed.
    static func compact(_ value: String) -> String {
        RfiHTML.collapsingWhitespace(value.replacingOccurrences(of: "\u{00A0}", with: " "))
    }

    private static func normalizeHeader(_ value: String) -> String {
        compact(value).uppercased()
    }

    /// Android's `normalizeTime`: the first «h.mm» or «hh:mm», as «hh:mm»; empty without one.
    static func normalizeTime(_ value: String) -> String {
        guard let match = timePattern.firstMatch(in: value)?[0] ?? nil else { return "" }
        return padded(match.replacingOccurrences(of: ".", with: ":"))
    }

    private static func padded(_ time: String) -> String {
        time.count >= 5 ? time : String(repeating: "0", count: 5 - time.count) + time
    }

    /// Minutes of «hh:mm» (hours 0–23), `Int.max` when unreadable.
    static func minutes(_ value: String) -> Int {
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return Int.max }
        return hour * 60 + minute
    }

    static func names(_ station: Station, _ published: String) -> Bool {
        RailStopNameMatcher.matches(name: station.name, aliases: station.aliases, published: published)
    }

    /// Kotlin's `trim()`: the whitespace at both ends.
    private static func kotlinTrim(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - Routes and boards

/// A train of a programmed route: the row as the route lists it (its stops up to the destination) and when it leaves.
public struct RfiProgrammedDeparture: Equatable, Sendable {
    public let row: RfiProgrammedRow
    public let day: RailDay
    public let departure: Date
}

public enum RfiProgrammedRoutes {
    /// Android's `loadRoute` after the page is read: the rows leaving `origin` on `serviceDay` from `requestedMinutes`
    /// (earlier rows are the next day's), valid that day and running by their periodicity, that call at
    /// `destination` after the origin or end there, their stops cut at the destination, in time order, the first
    /// `maximumRouteServices`. Empty when the page is not valid on `serviceDay`.
    public static func departures(
        _ page: RfiProgrammedPage, destination: Station, serviceDay: RailDay, requestedMinutes: Int
    ) -> [RfiProgrammedDeparture] {
        guard page.isValid(on: serviceDay) else { return [] }
        var found: [RfiProgrammedDeparture] = []
        for row in page.rows {
            let rowMinutes = RfiProgrammedParser.minutes(row.scheduled)
            guard let day = ItalianRailServiceCalendar.rfiDepartureDay(
                rowMinutes: rowMinutes, serviceDay: serviceDay, requestedMinutes: requestedMinutes
            ) else { continue }
            let boardDay = day.adding(days: row.boardDayOffset)
            guard page.isValid(on: boardDay), ItalianRailServiceCalendar.runsOnDate(row.periodicity, boardDay) else { continue }
            let target = row.stops.firstIndex { RfiProgrammedParser.names(destination, $0.station) }
            let callsThere = row.stops.dropFirst().contains { RfiProgrammedParser.names(destination, $0.station) }
            guard callsThere || RfiProgrammedParser.names(destination, row.destination) else { continue }
            let stops = target.map { $0 >= 1 ? Array(row.stops[...$0]) : row.stops } ?? row.stops
            guard rowMinutes != Int.max, let departure = day.italianDate(hour: rowMinutes / 60, minute: rowMinutes % 60) else { continue }
            let cut = RfiProgrammedRow(
                number: row.number, scheduled: row.scheduled, destination: row.destination, stops: stops,
                operatorKind: row.operatorKind, category: row.category, periodicity: row.periodicity,
                boardDayOffset: row.boardDayOffset
            )
            found.append(RfiProgrammedDeparture(row: cut, day: day, departure: departure))
        }
        return Array(found.sorted { $0.departure < $1.departure }.prefix(SharedData.RfiProgrammed.maximumRouteServices))
    }

    /// Android's `RfiProgrammedStationBoardAdapter`: the rows of each day from `from` to `to` that are valid and run,
    /// leaving within the window, each train once, in time order.
    public static func board(_ page: RfiProgrammedPage, from: Date, to: Date) -> [RfiProgrammedDeparture] {
        var days: [RailDay] = []
        var day = RailDay(italianDayOf: from)
        let last = RailDay(italianDayOf: to)
        while day <= last, days.count < 8 {
            days.append(day)
            day = day.adding(days: 1)
        }
        var seen = Set<String>()
        var found: [RfiProgrammedDeparture] = []
        for day in days where page.isValid(on: day) {
            for row in page.rows where ItalianRailServiceCalendar.runsOnDate(row.periodicity, day) {
                let rowMinutes = RfiProgrammedParser.minutes(row.scheduled)
                guard rowMinutes != Int.max,
                      let departure = day.italianDate(hour: rowMinutes / 60, minute: rowMinutes % 60),
                      departure >= from, departure <= to,
                      seen.insert("\(row.number):\(departure.timeIntervalSince1970):\(row.destination)").inserted else { continue }
                found.append(RfiProgrammedDeparture(row: row, day: day, departure: departure))
            }
        }
        return found.sorted { $0.departure < $1.departure }
    }
}

/// The programmed timetable of RFI, asked over HTTPS with Binario's manners: a page is asked once for every reader
/// for `pageTimeToLiveSeconds` and read once for `timetableTimeToLiveSeconds`; RFI follows no redirect (Android's
/// fetcher does not either); one refusal (403, 429) pauses every request (`LiveSourceGate`). Nothing about the user
/// is sent but the public timetable identifier of a hub.
public actor RfiProgrammedTimetable {
    public static let sourceLabel = "RFI — Quadro orario programmato"

    private let client: LiveSourceClient
    private let gate: LiveSourceGate
    private let now: @Sendable () -> Date
    private var pages: [URL: (html: String, fetchedAt: Date)] = [:]
    private var pageOrder: [URL] = []
    private var parsed: [String: (page: RfiProgrammedPage, expiresAt: Date)] = [:]
    private var parsedOrder: [String] = []

    public init(transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
                now: @escaping @Sendable () -> Date = { Date() }) {
        client = LiveSourceClient(transport: transport, now: now)
        gate = LiveSourceGate(
            source: nil,
            configuration: .init(minimumInterval: SharedData.RfiProgrammed.boardMinimumIntervalSeconds,
                                 maximumEntries: SharedData.RfiProgrammed.maximumPages),
            pauses: pauses, now: now
        )
        self.now = now
    }

    static let policy = LiveRequestPolicy(
        accept: SharedData.RfiProgrammed.accept,
        requestTimeout: SharedData.RfiProgrammed.readTimeoutSeconds,
        totalTimeout: SharedData.RfiProgrammed.totalTimeoutSeconds,
        maximumBytes: SharedData.RfiProgrammed.maximumBytes,
        maximumRequests: 1
    )

    /// The programmed trains from `origin` to `destination` leaving from `departure`, as Android's main adapter (the
    /// origin's departures, else the destination's arrivals) and then its supplemental one (a supplemental hub's
    /// departures); nil when no hub serves the route or RFI does not answer with a train.
    public func journeys(origin: Station, destination: Station, departure: Date, query: JourneyQuery) async -> JourneySnapshot? {
        let serviceDay = RailDay(italianDayOf: departure)
        let parts = LiveRailTime.calendar.dateComponents([.hour, .minute], from: departure)
        let requested = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        var readings: [(id: Int, arrivals: Bool)] = []
        if let id = RfiProgrammedStations.mainTimetableId(origin) {
            readings.append((id, false))
        } else if let id = RfiProgrammedStations.mainTimetableId(destination) ?? RfiProgrammedStations.supplementalTimetableId(destination) {
            readings.append((id, true))
        }
        if let id = RfiProgrammedStations.supplementalTimetableId(origin) { readings.append((id, false)) }
        for reading in readings {
            guard let page = await page(timetableId: reading.id, arrivals: reading.arrivals, origin: origin, destination: destination) else { continue }
            let trains = RfiProgrammedRoutes.departures(page, destination: destination, serviceDay: serviceDay, requestedMinutes: requested)
            guard !trains.isEmpty else { continue }
            let services = trains.map { train -> TrainService in
                let id = "rfi-programmed:\(train.day):\(train.row.number):\(train.row.scheduled)"
                return Self.service(train.row, id: id, destination: train.row.stops.last?.station ?? train.row.destination)
            }
            let generatedAt = now()
            return JourneySnapshot(
                query: query, generatedAt: generatedAt,
                expiresAt: generatedAt.addingTimeInterval(SharedData.RfiProgrammed.timetableTimeToLiveSeconds),
                operationalState: .programmed,
                provenance: DataProvenance(
                    producer: .iosApp, nature: .programmed, sourceLabel: Self.sourceLabel, observedAt: generatedAt,
                    freshness: .aging, confidence: .infrastructurePrimary
                ),
                services: services
            )
        }
        return nil
    }

    /// «Partenze» from RFI's departures page of a hub: the trains from `query.from` to a day later, after the cursor,
    /// `query.limit` of them; nil when no hub is the station or RFI does not answer.
    public func departures(station: Station, query: StationBoardQuery) async -> StationBoardPage? {
        guard let id = RfiProgrammedStations.mainTimetableId(station) ?? RfiProgrammedStations.supplementalTimetableId(station),
              let page = await page(timetableId: id, arrivals: false, origin: station, destination: nil) else { return nil }
        let trains = RfiProgrammedRoutes.board(page, from: query.from, to: query.from.addingTimeInterval(24 * 60 * 60))
        var entries: [StationBoardDeparture] = []
        for train in trains {
            let id = "rfi-programmed:\(station.id):\(train.day):\(train.row.number):\(train.row.scheduled)"
            let service = Self.service(train.row, id: id, destination: train.row.destination)
            let entry = StationBoardDeparture(
                departure: train.departure, service: service,
                terminus: StationIdentity(id: Self.terminusID(train.row.destination), name: train.row.destination)
            )
            if let after = query.after, !(after < entry.cursor) { continue }
            entries.append(entry)
        }
        guard !entries.isEmpty else { return nil }
        return StationBoardPage(
            query: query, departures: Array(entries.prefix(query.limit)), hasMore: entries.count > query.limit,
            firstDepartureNextDay: nil, attributions: [Self.sourceLabel]
        )
    }

    /// A programmed row as the iPhone lists a train: its number, category and operator, scheduled only.
    static func service(_ row: RfiProgrammedRow, id: String, destination: String) -> TrainService {
        TrainService(
            id: id, number: row.number, operatorName: row.operatorKind.displayName ?? "RFI", category: row.category.label,
            destination: destination, scheduledDeparture: row.scheduled, expectedDeparture: row.scheduled,
            status: OperationalState.programmed.rawValue, platform: nil, delayMinutes: 0
        )
    }

    /// Android's `rfi-destination:<name>`: the terminus of a programmed row, which no catalogue station is bound to.
    static func terminusID(_ name: String) -> String {
        var slug = ""
        var dash = false
        for scalar in name.lowercased().unicodeScalars {
            if ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) {
                if dash, !slug.isEmpty { slug.append("-") }
                dash = false
                slug.unicodeScalars.append(scalar)
            } else {
                dash = true
            }
        }
        return "rfi-destination:" + String((slug.isEmpty ? "unknown" : slug).prefix(96))
    }

    /// The read page of a hub for `origin` (and, on an arrivals page, `destination`), from memory or from RFI.
    private func page(timetableId: Int, arrivals: Bool, origin: Station, destination: Station?) async -> RfiProgrammedPage? {
        let key = "\(arrivals ? "A" : "P"):\(timetableId):\(origin.id):\(arrivals ? destination?.id ?? "" : "")"
        let current = now()
        if let kept = parsed[key], current < kept.expiresAt { return kept.page }
        guard let url = RfiProgrammedStations.url(timetableId: timetableId, arrivals: arrivals),
              let html = await html(url) else { return nil }
        let page = RfiProgrammedParser.parse(html, origin: origin, destinationName: destination?.name ?? "", arrivals: arrivals)
        if parsed[key] == nil { parsedOrder.append(key) }
        parsed[key] = (page, current.addingTimeInterval(SharedData.RfiProgrammed.timetableTimeToLiveSeconds))
        let limit = SharedData.RfiProgrammed.maximumTimetables + SharedData.RfiProgrammed.maximumSupplementalTimetables
        while parsedOrder.count > limit { parsed[parsedOrder.removeFirst()] = nil }
        return page
    }

    private func html(_ url: URL) async -> String? {
        let current = now()
        if let kept = pages[url], current.timeIntervalSince(kept.fetchedAt) < SharedData.RfiProgrammed.pageTimeToLiveSeconds {
            return kept.html
        }
        let client = self.client
        let result = await gate.fetch(url.absoluteString) { await client.get(url, policy: Self.policy) }
        guard case let .body(body) = result, !body.text.isEmpty else { return nil }
        if pages[url] == nil { pageOrder.append(url) }
        pages[url] = (body.text, current)
        while pageOrder.count > SharedData.RfiProgrammed.maximumPages { pages[pageOrder.removeFirst()] = nil }
        return body.text
    }
}
