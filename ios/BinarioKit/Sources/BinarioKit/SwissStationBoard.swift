import Foundation

// The Swiss station boards of transport.opendata.ch (Android's `SwissOpenTransportStationBoardAdapter`): a public API
// without a token, asked for Swiss and near-border stations only, with the limits of the shared table
// (`SharedData.RealtimeFeeds.swissBoard`). The cases both platforms read are
// contracts/shared-rules/v1/swiss-station-board-v1.json.

/// Swiss station numbers usable with transport.opendata.ch (`SwissStationIds`): the catalogue stores a Swiss station
/// with a Trenitalia location id `85` + `00` + its five-digit number (Bellinzona `850005213` is UIC `8505213`); a
/// stop of the Swiss pack carries the UIC in its identifier or, since the 2026 archive, a SLOID whose number is the station
/// number without the country code (`ch:1:sloid:5213:0:1` is UIC `8505213`). Trenitalia's `85 009xxxx` ids are city
/// aggregates (Basilea), not stations.
enum SwissStationIds {
    private static let gtfsStop = LiveRegex("^gtfs:ch-otd-gtfs:(?:Parent)?(85\\d{5})(?::.*)?$")
    private static let gtfsSloid = LiveRegex("^gtfs:ch-otd-gtfs:(?:Parent)?ch:1:sloid:([1-9]\\d{0,4})(?::.*)?$")
    private static let trenitaliaSwiss = LiveRegex("^8500([0-8]\\d{4})$")
    private static let uic = LiveRegex("^85\\d{5}$")

    /// The UIC of a station, nil when its identifiers do not give one.
    static func uic(stationID: String, trenitaliaLocationID: String?) -> String? {
        if let match = gtfsStop.firstMatch(in: stationID), let number = match[1] { return number }
        if let match = gtfsSloid.firstMatch(in: stationID), let digits = match[1] {
            return "85" + String(repeating: "0", count: 5 - digits.count) + digits
        }
        guard let location = trenitaliaLocationID?.trimmingCharacters(in: .whitespacesAndNewlines),
              let match = trenitaliaSwiss.firstMatch(in: location), let digits = match[1] else { return nil }
        return "85" + digits
    }

    /// The catalogue says the station is Swiss but carries no usable UIC.
    static func isSwissWithoutUIC(stationID: String, trenitaliaLocationID: String?) -> Bool {
        trenitaliaLocationID?.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("85") == true
            && uic(stationID: stationID, trenitaliaLocationID: trenitaliaLocationID) == nil
    }

    static func isUIC(_ value: String) -> Bool { uic.contains(value) }
}

/// One station of a `locations` answer.
struct SwissLocation: Equatable, Sendable {
    let id: String
    let name: String
    let icon: String?
    let latitude: Double?
    let longitude: Double?
}

/// One departure of a Swiss board as the API publishes it, kept only when it is a train with a journey number.
struct SwissBoardRow: Equatable, Sendable {
    /// The journey number without padding ("25138"), the train's identity on every board.
    let journeyNumber: String
    /// The commercial line ("S 10", "EC 29"), nil without a category.
    let lineLabel: String?
    let operatorID: String?
    let destination: String
    let scheduled: Date
    let expected: Date?
    let platform: String?
    /// The planned platform, kept only when the prognosis moved the train elsewhere.
    let scheduledPlatform: String?
}

/// The payloads of transport.opendata.ch read as Android reads them. Pure functions; nil is an unreadable answer.
enum SwissBoardParser {
    static let table = SharedData.RealtimeFeeds.swissBoard
    static let zone = TimeZone(identifier: table.zone) ?? TimeZone(secondsFromGMT: 3_600) ?? .current
    private static let dateTime = LiveRegex(#"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(Z|[+-]\d{2}(?::?\d{2})?)$"#)

    /// The departures of `uic`'s board, nil when the answer is not that station's board.
    static func departures(_ body: String, uic: String) -> [SwissBoardRow]? {
        guard let root = LiveJSONObject(LiveJSON.parse(body)), let station = root.object("station"),
              text(station, "id") == uic, let rows = root.array("stationboard") else { return nil }
        return rows.prefix(table.maximumRows).compactMap { item -> SwissBoardRow? in
            guard let row = LiveJSONObject(item) else { return nil }
            return departure(row)
        }
    }

    private static func departure(_ row: LiveJSONObject) -> SwissBoardRow? {
        let category = text(row, "category").map { String($0.uppercased().prefix(8)) }
        if let category, table.nonRailCategories.contains(category) { return nil }
        guard let stop = row.object("stop"), let scheduled = date(text(stop, "departure")),
              let journeyNumber = digitsOrNil(text(row, "name")),
              let destination = text(row, "to"), !destination.allSatisfy(\.isWhitespace) else { return nil }
        let prognosis = stop.object("prognosis")
        let trusted = date(prognosis.flatMap { text($0, "departure") }).flatMap { plausible($0, scheduled: scheduled) ? $0 : nil }
        let delay = intValue(stop.values["delay"]).flatMap { (-1...table.maximumDelayMinutes).contains($0) ? $0 : nil }
        let expected = trusted ?? delay.map { scheduled.addingTimeInterval(TimeInterval($0) * 60) }
        let lineNumber = text(row, "number").map { digitsOrNil($0) ?? String($0.prefix(8)) }
        let lineLabel = category.flatMap { value -> String? in
            guard !value.allSatisfy(\.isWhitespace) else { return nil }
            return [value, lineNumber].compactMap { $0 }.joined(separator: " ")
        }
        let movedPlatform = prognosis.flatMap { text($0, "platform") }
        return SwissBoardRow(
            journeyNumber: journeyNumber,
            lineLabel: lineLabel,
            operatorID: text(row, "operator").flatMap(operatorID),
            destination: String(destination.prefix(120)),
            scheduled: scheduled,
            expected: expected,
            platform: (movedPlatform ?? text(stop, "platform")).map { String($0.prefix(12)) },
            scheduledPlatform: movedPlatform == nil ? nil : text(stop, "platform").map { String($0.prefix(12)) }
        )
    }

    /// A prognosis within a sane window: a departure never leaves early (one minute at most).
    private static func plausible(_ prognosis: Date, scheduled: Date) -> Bool {
        let minutes: Double = (prognosis.timeIntervalSince(scheduled) / 60).rounded(.towardZero)
        return minutes >= -1 && minutes <= Double(table.maximumDelayMinutes)
    }

    /// The stations of a `locations` answer (the first `maximumLocations`), nil without a station list.
    static func locations(_ body: String) -> [SwissLocation]? {
        guard let root = LiveJSONObject(LiveJSON.parse(body)), let stations = root.array("stations") else { return nil }
        return stations.prefix(table.maximumLocations).compactMap { item -> SwissLocation? in
            guard let station = LiveJSONObject(item), let id = text(station, "id") else { return nil }
            // The API publishes the WGS84 latitude as `x` and the longitude as `y`.
            let coordinate = station.object("coordinate")
            let latitude = coordinate.flatMap { double($0.values["x"]) }
            let longitude = coordinate.flatMap { double($0.values["y"]) }
            let valid = latitude.map { (-90...90).contains($0) } == true && longitude.map { (-180...180).contains($0) } == true
            return SwissLocation(
                id: id, name: text(station, "name") ?? "", icon: text(station, "icon"),
                latitude: valid ? latitude : nil, longitude: valid ? longitude : nil
            )
        }
    }

    /// The train station of `candidates` a name lookup may use: a Swiss UIC with the train icon, within the shared
    /// distance of the station's coordinates, or with the same normalised name when they are unknown.
    static func selectLocation(_ candidates: [SwissLocation], name: String, latitude: Double?, longitude: Double?) -> String? {
        candidates.first { candidate in
            guard candidate.icon == "train", SwissStationIds.isUIC(candidate.id) else { return false }
            if let latitude, let longitude {
                guard let placeLatitude = candidate.latitude, let placeLongitude = candidate.longitude else { return false }
                return distanceMeters(latitude, longitude, placeLatitude, placeLongitude) <= Double(table.maximumLocationDistanceMeters)
            }
            return normalizeName(candidate.name) == normalizeName(name)
        }?.id
    }

    /// Android's `operatorId`: the operators the source scores know by name, else a slug of the published name.
    static func operatorID(_ raw: String) -> String? {
        let value = raw.lowercased()
        if value.allSatisfy(\.isWhitespace) { return nil }
        if value.contains("tilo") { return "tilo" }
        if LiveRegex("(^|[^a-z])sbb([^a-z]|$)").contains(value) { return "sbb" }
        if value.contains("bls") { return "bls" }
        if value == "rhb" || value.contains("rhätische") || value.contains("rhaetische") { return "rhb" }
        if value.contains("fart") || value.contains("ssif") { return "fart-ssif" }
        if value.contains("trenord") { return "trenord" }
        if value.contains("trenitalia") { return "trenitalia" }
        // The runs of letters and digits joined by dashes (none at either end), then cut.
        var slug = ""
        var pendingDash = false
        for scalar in value.unicodeScalars {
            if ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) {
                if pendingDash, !slug.isEmpty { slug.append("-") }
                pendingDash = false
                slug.unicodeScalars.append(scalar)
            } else {
                pendingDash = true
            }
        }
        let cut = String(slug.prefix(24))
        return cut.isEmpty ? nil : cut
    }

    /// Android's `normalizeName`: accents removed (NFD without marks), upper case, other characters as single spaces.
    static func normalizeName(_ value: String) -> String {
        let decomposed = value.decomposedStringWithCanonicalMapping.unicodeScalars.filter { scalar in
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark: return false
            default: return true
            }
        }
        let upper = String(String.UnicodeScalarView(decomposed)).uppercased()
        var result = ""
        var pendingSpace = false
        for scalar in upper.unicodeScalars {
            if ("A"..."Z").contains(scalar) || ("0"..."9").contains(scalar) {
                if pendingSpace, !result.isEmpty { result.append(" ") }
                pendingSpace = false
                result.unicodeScalars.append(scalar)
            } else {
                pendingSpace = true
            }
        }
        return result
    }

    /// The board's journey number: one to eight digits without leading zeros, nil otherwise.
    static func digitsOrNil(_ value: String?) -> String? {
        guard let value, LiveText.isDigits(value, count: 1...8) else { return nil }
        let trimmed = String(value.drop { $0 == "0" })
        return trimmed.isEmpty ? nil : trimmed
    }

    /// "2026-10-25T21:30:00+0100" (or "+01:00", "Z"); a time without an offset is no time.
    static func date(_ value: String?) -> Date? {
        guard let value, let match = dateTime.firstMatch(in: value), match.count == 8,
              let year = match[1].flatMap({ Int($0) }), let month = match[2].flatMap({ Int($0) }),
              let day = match[3].flatMap({ Int($0) }), let hour = match[4].flatMap({ Int($0) }),
              let minute = match[5].flatMap({ Int($0) }), let second = match[6].flatMap({ Int($0) }),
              let offsetText = match[7], let offset = offsetSeconds(offsetText),
              (1...12).contains(month), (1...31).contains(day), (0...23).contains(hour), (0...59).contains(minute),
              (0...59).contains(second), let timeZone = TimeZone(secondsFromGMT: offset) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = timeZone
        // Java's smart resolver takes a day past the month's end as its last day.
        let monthStart = calendar.date(from: DateComponents(year: year, month: month, day: 1))
        let lastDay = monthStart.flatMap { calendar.range(of: .day, in: .month, for: $0)?.count } ?? 28
        let components = DateComponents(year: year, month: month, day: min(day, lastDay), hour: hour, minute: minute, second: second)
        return calendar.date(from: components)
    }

    private static func offsetSeconds(_ text: String) -> Int? {
        if text == "Z" { return 0 }
        let sign = text.hasPrefix("-") ? -1 : 1
        let digits = text.dropFirst().filter { $0 != ":" }
        guard digits.count == 2 || digits.count == 4, let hours = Int(digits.prefix(2)) else { return nil }
        let minutes = digits.count == 4 ? Int(digits.suffix(2)) ?? 99 : 0
        guard hours <= 18, minutes <= 59 else { return nil }
        return sign * (hours * 3_600 + minutes * 60)
    }

    /// Android's `text`: trimmed, nil when missing, JSON null, empty or the word «null».
    private static func text(_ object: LiveJSONObject, _ key: String) -> String? {
        guard let value = LiveJSON.text(object.values[key]), value != "null" else { return nil }
        return value
    }

    private static func intValue(_ value: Any?) -> Int? {
        switch value {
        case let number as NSNumber:
            let double = number.doubleValue
            guard double.isFinite else { return nil }
            return Int(Int32(clamping: Int64(max(min(double.rounded(.towardZero), 9.2e18), -9.2e18))))
        case let string as String:
            return Int(string.trimmingCharacters(in: .whitespacesAndNewlines))
        default:
            return nil
        }
    }

    /// org.json's `optDouble`: a number, or the number a text holds.
    private static func double(_ value: Any?) -> Double? {
        let double: Double?
        switch value {
        case let number as NSNumber: double = number.doubleValue
        case let string as String: double = Double(string.trimmingCharacters(in: .whitespacesAndNewlines))
        default: double = nil
        }
        guard let double, double.isFinite else { return nil }
        return double
    }

    /// The haversine distance on a 6,371 km sphere, as Android computes it.
    static func distanceMeters(_ latitude: Double, _ longitude: Double, _ otherLatitude: Double, _ otherLongitude: Double) -> Double {
        let first = latitude * .pi / 180
        let second = otherLatitude * .pi / 180
        let deltaLatitude = second - first
        let deltaLongitude = (otherLongitude - longitude) * .pi / 180
        let a = pow(sin(deltaLatitude / 2), 2) + cos(first) * cos(second) * pow(sin(deltaLongitude / 2), 2)
        return 2 * 6_371_000 * asin(min(max(a, 0), 1).squareRoot())
    }

    /// `URLEncoder.encode(value, "UTF-8")`: letters, digits and «.-*_» as they are, a space as «+», every other byte
    /// percent-encoded.
    static func formEncoded(_ value: String) -> String {
        var result = ""
        for byte in value.utf8 {
            switch byte {
            case 0x30...0x39, 0x41...0x5A, 0x61...0x7A, 0x2E, 0x2D, 0x2A, 0x5F:
                result.unicodeScalars.append(Unicode.Scalar(byte))
            case 0x20:
                result.append("+")
            default:
                result += "%" + String(byte, radix: 16, uppercase: true).leftPadded(to: 2)
            }
        }
        return result
    }
}

private extension String {
    func leftPadded(to width: Int) -> String {
        count >= width ? self : String(repeating: "0", count: width - count) + self
    }
}

/// The boards of transport.opendata.ch as a live departures source, Android's `SwissOpenTransportStationBoardAdapter`
/// in «Partenze» and in the planner's results: only for a station with a Swiss UIC, or a near-border or Swiss station
/// found once by name (`locations`, kept a week); a board is reused for a minute, rows only with a journey number,
/// prognoses only within their window. HTTPS to transport.opendata.ch only; the pause is the shared one (one request
/// at a time, a pause at the first refusal and after repeated failures). Android retries a dropped connection once
/// after 750 ms; as for the other live sources of the iPhone, nothing is retried by itself.
public struct SwissStationBoardSource: LiveDepartureBoardSource {
    public let source: LiveSource = .swissOpenTransport

    static let table = SharedData.RealtimeFeeds.swissBoard
    static let policy = LiveRequestPolicy(
        accept: table.accept, requestTimeout: table.readTimeoutSeconds, totalTimeout: table.totalTimeoutSeconds,
        maximumBytes: table.maximumBytes, maximumRequests: table.maximumRequests
    )

    let client: LiveSourceClient
    let gate: LiveSourceGate
    let cache: SwissBoardCache

    init(client: LiveSourceClient, gate: LiveSourceGate, cache: SwissBoardCache = SwissBoardCache()) {
        self.client = client
        self.gate = gate
        self.cache = cache
    }

    public init(
        transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.init(
            client: LiveSourceClient(transport: transport, now: now),
            gate: LiveSourceGate(
                source: .swissOpenTransport, configuration: .init(minimumInterval: Self.table.minimumIntervalSeconds),
                pauses: pauses, now: now
            )
        )
    }

    public func departures(for window: LiveBoardWindow) async -> LiveSourceAnswer<[LiveDeparture]> {
        // Android's `SwissOpenTransportStationBoardAdapter` lists departures only.
        guard window.kind == .departures else { return .unavailable(.notCovered) }
        let station = window.station
        let uic: String
        if let known = SwissStationIds.uic(stationID: station.id, trenitaliaLocationID: station.trenitaliaLocationId) {
            uic = known
        } else if let found = await locate(station) {
            uic = found
        } else {
            if Task.isCancelled { return .unavailable(.cancelled) }
            return .unavailable(.notCovered)
        }
        let now = client.now()
        let address = Self.boardURL(uic: uic, from: window.from, now: now)
        let rows: [SwissBoardRow]
        let fetchedAt: Date
        if let kept = cache.board(address, at: now) {
            rows = kept.rows
            fetchedAt = kept.fetchedAt
        } else {
            guard let url = URL(string: address) else { return .unavailable(.invalidRequest) }
            let client = self.client
            // An answer that is not the station's board is a failure of the source, as Android's parse error.
            let load: @Sendable () async -> LiveFetchResult = {
                let result = await client.get(url, policy: Self.policy)
                if case let .body(body) = result, SwissBoardParser.departures(body.text, uic: uic) == nil { return .failure(.network) }
                return result
            }
            switch await gate.fetch(address, load: load) {
            case let .failure(failure):
                return .unavailable(failure.unavailableReason)
            case let .body(body):
                guard let parsed = SwissBoardParser.departures(body.text, uic: uic) else { return .unavailable(.unreadable) }
                cache.store(board: parsed, for: address, fetchedAt: body.fetchedAt)
                rows = parsed
                fetchedAt = body.fetchedAt
            }
        }
        return .answered(Self.departures(rows, uic: uic, window: window, fetchedAt: fetchedAt), complete: true)
    }

    /// The rows the window keeps, as live departures: the expected time and its state, the platform, the journey
    /// number as the train's number, all observed when the board was received and live for `boardTimeToLiveSeconds`.
    static func departures(_ rows: [SwissBoardRow], uic: String, window: LiveBoardWindow, fetchedAt: Date) -> [LiveDeparture] {
        let observation = LiveObservation(
            source: .swissOpenTransport, fetchedAt: fetchedAt, observedAt: fetchedAt,
            expiresAt: fetchedAt.addingTimeInterval(table.boardTimeToLiveSeconds)
        )
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = SwissBoardParser.zone
        return rows.filter { window.keeps(scheduled: $0.scheduled, expected: $0.expected) }
            .sorted { $0.scheduled < $1.scheduled }
            .map { row in
                let day = calendar.dateComponents([.year, .month, .day], from: row.scheduled)
                let date = LiveText.padded(day.year ?? 0, width: 4) + "-" + LiveText.padded(day.month ?? 0, width: 2) + "-"
                    + LiveText.padded(day.day ?? 0, width: 2)
                let status: LiveDepartureStatus? = row.expected.map { $0 > row.scheduled.addingTimeInterval(59) ? .delayed : .onTime }
                let platform = row.platform.flatMap { $0.allSatisfy(\.isWhitespace) ? nil : $0 }
                return LiveDeparture(
                    id: "\(table.id):\(row.journeyNumber):\(date)", number: row.journeyNumber, operatorID: row.operatorID,
                    category: row.lineLabel ?? "", destination: row.destination, scheduled: row.scheduled,
                    expected: row.expected.map { LiveSourced($0, observation: observation) },
                    platform: platform.map { LiveSourced($0, observation: observation) },
                    scheduledPlatform: platform == nil ? nil : row.scheduledPlatform.flatMap { $0.allSatisfy(\.isWhitespace) ? nil : $0 },
                    status: status.map { LiveSourced($0, observation: observation) },
                    sources: [.swissOpenTransport], viaggiaTrenoRun: nil, observation: observation
                )
            }
    }

    /// Android's `boardUrl`: the departures of trains, from now, or from the window's start written in Swiss time
    /// when it is not about now.
    static func boardURL(uic: String, from: Date, now: Date) -> String {
        var address = "\(table.baseUrl)/stationboard?id=\(uic)&limit=\(table.boardLimit)&transportations%5B%5D=train"
        let current = from >= now.addingTimeInterval(-TimeInterval(table.currentBoardBeforeMinutes) * 60)
            && from <= now.addingTimeInterval(TimeInterval(table.currentBoardAfterMinutes) * 60)
        if !current {
            var calendar = Calendar(identifier: .gregorian)
            calendar.locale = Locale(identifier: "en_US_POSIX")
            calendar.timeZone = SwissBoardParser.zone
            let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: from)
            let local = LiveText.padded(parts.year ?? 0, width: 4) + "-" + LiveText.padded(parts.month ?? 0, width: 2) + "-"
                + LiveText.padded(parts.day ?? 0, width: 2) + " " + LiveText.padded(parts.hour ?? 0, width: 2) + ":"
                + LiveText.padded(parts.minute ?? 0, width: 2)
            address += "&datetime=" + SwissBoardParser.formEncoded(local).replacingOccurrences(of: "+", with: "%20")
        }
        return address
    }

    /// Android's `resolveByName`: only for a station the catalogue marks as Swiss without a usable UIC, or a
    /// near-border station with coordinates; once per name, kept for a week.
    private func locate(_ station: Station) async -> String? {
        let name = station.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...80).contains(name.count) else { return nil }
        let swiss = SwissStationIds.isSwissWithoutUIC(stationID: station.id, trenitaliaLocationID: station.trenitaliaLocationId)
        let nearBorder = Self.inBorderRegion(latitude: station.latitude, longitude: station.longitude)
        guard swiss || nearBorder else { return nil }
        let address = "\(Self.table.baseUrl)/locations?query=\(SwissBoardParser.formEncoded(name))&type=station"
        let candidates: [SwissLocation]
        if let kept = cache.locations(address, at: client.now()) {
            candidates = kept
        } else {
            guard let url = URL(string: address) else { return nil }
            let client = self.client
            guard case let .body(body) = await gate.fetch(address, load: { await client.get(url, policy: Self.policy) }),
                  let parsed = SwissBoardParser.locations(body.text) else { return nil }
            cache.store(locations: parsed, for: address, fetchedAt: body.fetchedAt)
            candidates = parsed
        }
        return SwissBoardParser.selectLocation(candidates, name: name, latitude: station.latitude, longitude: station.longitude)
    }

    /// Android's `inBorderRegion`, for a station with both coordinates.
    static func inBorderRegion(latitude: Double?, longitude: Double?) -> Bool {
        guard let latitude, let longitude else { return false }
        let region = table.borderRegion
        return (region.minimumLatitude...region.maximumLatitude).contains(latitude)
            && (region.minimumLongitude...region.maximumLongitude).contains(longitude)
    }
}

/// The boards (a minute) and the name lookups (a week) of transport.opendata.ch, in memory only.
final class SwissBoardCache: @unchecked Sendable {
    static let maximumBoards = 24
    static let maximumLocations = 64

    private let lock = NSLock()
    private var boards: [String: (rows: [SwissBoardRow], fetchedAt: Date)] = [:]
    private var boardOrder: [String] = []
    private var found: [String: (stations: [SwissLocation], fetchedAt: Date)] = [:]
    private var foundOrder: [String] = []

    func board(_ address: String, at now: Date) -> (rows: [SwissBoardRow], fetchedAt: Date)? {
        lock.lock()
        defer { lock.unlock() }
        guard let kept = boards[address], now < kept.fetchedAt.addingTimeInterval(SwissStationBoardSource.table.boardTimeToLiveSeconds) else { return nil }
        return kept
    }

    func store(board rows: [SwissBoardRow], for address: String, fetchedAt: Date) {
        lock.lock()
        if boards[address] == nil { boardOrder.append(address) }
        boards[address] = (rows, fetchedAt)
        while boardOrder.count > Self.maximumBoards { boards[boardOrder.removeFirst()] = nil }
        lock.unlock()
    }

    func locations(_ address: String, at now: Date) -> [SwissLocation]? {
        lock.lock()
        defer { lock.unlock() }
        guard let kept = found[address],
              now < kept.fetchedAt.addingTimeInterval(SwissStationBoardSource.table.locationTimeToLiveSeconds) else { return nil }
        return kept.stations
    }

    func store(locations stations: [SwissLocation], for address: String, fetchedAt: Date) {
        lock.lock()
        if found[address] == nil { foundOrder.append(address) }
        found[address] = (stations, fetchedAt)
        while foundOrder.count > Self.maximumLocations { found[foundOrder.removeFirst()] = nil }
        lock.unlock()
    }
}
