import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Public place IDs from the RFI station selector. The same file ships on Android and Alexa.
public struct RfiStationCatalog: Sendable {
    private struct Document: Decodable {
        struct Entry: Decodable { let id: Int; let name: String }
        let schemaVersion: Int
        let stations: [Entry]
    }
    private let ids: [String: Int]

    public init(data: Data) throws {
        let document = try JSONDecoder().decode(Document.self, from: data)
        guard document.schemaVersion == 1, !document.stations.isEmpty,
              document.stations.allSatisfy({ $0.id > 0 && !$0.name.isEmpty }) else {
            throw RfiDataError.invalidCatalog
        }
        var ids: [String: Int] = [:]
        for entry in document.stations {
            let key = RfiHTML.stationKey(entry.name)
            guard ids[key] == nil || ids[key] == entry.id else { throw RfiDataError.invalidCatalog }
            ids[key] = entry.id
        }
        self.ids = ids
    }

    public func placeID(for name: String) -> Int? { ids[RfiHTML.stationKey(name)] }
}

private enum RfiDataError: Error { case invalidCatalog, invalidResponse, stale, wrongStation, rateLimited }

/// Foreground live departures first, with explicit schedule-only fallback on failure or future dates.
/// A station request contains a public place ID; no account, destination or location is transmitted.
public actor RfiJourneyRepository: JourneyRepository {
    public typealias Fetch = @Sendable (URL) async throws -> Data
    private let catalog: RfiStationCatalog
    private let fallback: any JourneyRepository
    private let fetch: Fetch
    private let now: @Sendable () -> Date
    private var cache: [Int: RfiBoard] = [:]
    private var pending: [Int: Task<RfiBoard, Error>] = [:]
    private var lastAttempt: [Int: Date] = [:]

    public init(
        catalog: RfiStationCatalog,
        fallback: any JourneyRepository = FailClosedJourneyRepository(),
        now: @escaping @Sendable () -> Date = { Date() },
        fetch: Fetch? = nil
    ) {
        self.catalog = catalog
        self.fallback = fallback
        self.now = now
        self.fetch = fetch ?? Self.fetchHTML
    }

    public func journeys(for query: JourneyQuery) async -> JourneyResult {
        let current = now()
        let calendar = RfiHTML.calendar
        guard calendar.isDate(query.departure, inSameDayAs: current),
              let placeID = catalog.placeID(for: query.origin.name) else {
            return await fallback.journeys(for: query)
        }
        do {
            let board = try await board(placeID: placeID)
            let target = RfiHTML.stationKey(query.destination.name)
            let departures = board.departures.filter {
                calendar.isDate($0.scheduled, inSameDayAs: query.departure)
                    && $0.expected >= query.departure.addingTimeInterval(-300)
                    && ($0.stopNames.contains(target) || RfiHTML.stationKey($0.service.destination) == target)
            }
            guard !departures.isEmpty, !Task.isCancelled else { return await fallback.journeys(for: query) }
            return .available(JourneySnapshot(
                query: query, generatedAt: board.fetchedAt, expiresAt: board.expiresAt,
                operationalState: .operational,
                provenance: DataProvenance(
                    producer: .iosApp, nature: .operational,
                    sourceLabel: "RFI — Monitor Arrivi/Partenze", observedAt: board.observedAt,
                    freshness: .fresh, confidence: .infrastructurePrimary
                ),
                services: departures.map(\.service)
            ))
        } catch {
            return await fallback.journeys(for: query)
        }
    }

    private func board(placeID: Int) async throws -> RfiBoard {
        let current = now()
        if let cached = cache[placeID], cached.expiresAt > current { return cached }
        if let task = pending[placeID] { return try await task.value }
        if let attempt = lastAttempt[placeID], current.timeIntervalSince(attempt) < 20 {
            throw RfiDataError.rateLimited
        }
        lastAttempt = lastAttempt.filter { current.timeIntervalSince($0.value) < 120 }
        lastAttempt[placeID] = current
        let fetch = self.fetch
        let now = self.now
        let catalog = self.catalog
        let task = Task<RfiBoard, Error> {
            guard let url = URL(string: "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=\(placeID)&arrivals=False") else {
                throw RfiDataError.invalidResponse
            }
            let data = try await fetch(url)
            guard data.count <= 2_000_000, let html = String(data: data, encoding: .utf8) else { throw RfiDataError.invalidResponse }
            let board = try RfiHTML.parse(html, fetchedAt: now())
            guard catalog.placeID(for: board.stationName) == placeID else { throw RfiDataError.wrongStation }
            return board
        }
        pending[placeID] = task
        defer { pending[placeID] = nil }
        let board = try await task.value
        cache = cache.filter { $0.value.expiresAt > now() }
        if cache.count >= 16, let key = cache.keys.first { cache[key] = nil }
        cache[placeID] = board
        return board
    }

    private static func fetchHTML(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
        request.setValue("Binario/1.0 (rail information client)", forHTTPHeaderField: "User-Agent")
        request.setValue("text/html", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        let session = URLSession(configuration: configuration, delegate: RfiRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        if #available(macOS 12, *) {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  response.url?.scheme == "https", response.url?.host == "iechub.rfi.it" else { throw RfiDataError.invalidResponse }
            return data
        }
        throw RfiDataError.invalidResponse
    }
}

private final class RfiRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

private struct RfiDeparture: Sendable {
    let service: TrainService
    let scheduled: Date
    let expected: Date
    let stopNames: Set<String>
}

private struct RfiBoard: Sendable {
    let stationName: String
    let observedAt: Date
    let fetchedAt: Date
    let expiresAt: Date
    let departures: [RfiDeparture]
}

/// Read only the documented public monitor DOM. No page scripts, assets or private endpoints.
private enum RfiHTML {
    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome")!
        return calendar
    }

    static func stationKey(_ value: String) -> String {
        value.folding(options: [.diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .uppercased().replacingOccurrences(of: #"\bS\.\s*"#, with: "SAN ", options: .regularExpression)
            .replacingOccurrences(of: "[^A-Z0-9]", with: "", options: .regularExpression)
    }

    static func parse(_ html: String, fetchedAt: Date) throws -> RfiBoard {
        let label = text(html)
        guard let timestamp = captures(#"aggiornato il\s+(\d{2}/\d{2}/\d{4})\s+alle ore\s+(\d{2}:\d{2}:\d{2})"#, label).first else {
            throw RfiDataError.invalidResponse
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "dd/MM/yyyy HH:mm:ss"
        formatter.isLenient = false
        guard let observed = formatter.date(from: "\(timestamp[1]) \(timestamp[2])"),
              observed <= fetchedAt.addingTimeInterval(60), observed.addingTimeInterval(120) > fetchedAt else { throw RfiDataError.stale }
        guard let heading = captures(#"<h1\b[^>]*>([\s\S]*?)</h1>"#, html).first else { throw RfiDataError.invalidResponse }
        let rows = captures(#"<tr\b[^>]*\bname\s*=\s*["']treno["'][^>]*>([\s\S]*?)</tr>"#, html)
        let departures = rows.compactMap { parseRow($0[1], observedAt: observed) }
        return RfiBoard(
            stationName: text(heading[1]), observedAt: observed, fetchedAt: fetchedAt,
            expiresAt: min(fetchedAt.addingTimeInterval(60), observed.addingTimeInterval(120)), departures: departures
        )
    }

    private static func parseRow(_ row: String, observedAt: Date) -> RfiDeparture? {
        let number = text(cell("RTreno", row))
        let destination = text(cell("RStazione", row))
        let time = text(cell("ROrario", row))
        guard number.range(of: #"^\d{1,8}$"#, options: .regularExpression) != nil,
              !destination.isEmpty, let parts = captures(#"^(\d{1,2}):(\d{2})$"#, time).first,
              let hour = Int(parts[1]), let minute = Int(parts[2]), hour < 24, minute < 60 else { return nil }
        let delayCell = cell("RRitardo", row)
        let delayTag = captures(#"<td\b[^>]*\bid=["']RRitardo["'][^>]*>"#, row).first?.first ?? ""
        let delayLabel = text(delayTag.replacingOccurrences(of: "&quot;", with: "\""))
        let explicitLabel = captures(#"aria-label\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))"#, delayTag)
            .first?.dropFirst().first(where: { !$0.isEmpty }).map { text($0).trimmingCharacters(in: CharacterSet(charactersIn: "\"")) } ?? delayLabel
        let statusText = text(delayCell).uppercased()
        let cancelled = statusText.contains("CANCELLATO") || statusText.contains("SOPPRESSO")
        let publishedDelay = captures(#"\b(\d{1,4})\b"#, explicitLabel.isEmpty ? statusText : explicitLabel).first.flatMap { Int($0[1]) }
        let onTime = explicitLabel.uppercased().contains("NESSUNO") || explicitLabel.uppercased().contains("IN ORARIO")
            || statusText.contains("IN ORARIO")
        guard cancelled || publishedDelay != nil || onTime else { return nil }
        let delay = cancelled ? 0 : (publishedDelay ?? 0)
        guard delay <= 1_440 else { return nil }
        let dateDigits = captures(#"\bbtn_(\d{4})(\d{2})(\d{2})"#, row).first
        var date = calendar.dateComponents([.year, .month, .day], from: observedAt)
        if let digits = dateDigits { date.year = Int(digits[1]); date.month = Int(digits[2]); date.day = Int(digits[3]) }
        date.hour = hour; date.minute = minute; date.second = 0
        guard var scheduled = calendar.date(from: date) else { return nil }
        if dateDigits == nil && scheduled < observedAt.addingTimeInterval(-12 * 3_600) {
            guard let following = calendar.date(byAdding: .day, value: 1, to: scheduled) else { return nil }
            scheduled = following
        }
        let expected = scheduled.addingTimeInterval(TimeInterval(delay * 60))
        let stopNames = Set(captures(#"(?:FERMA A:|\s+-\s+)([^<>]*?)\s*\(\d{1,2}:\d{2}\)"#, text(cell("RDettagli", row))).map { stationKey($0[1]) })
        let platformText = text(cell("RBinario", row))
        let platform = ["", "PF", "N/D", "-"].contains(platformText.uppercased()) ? nil : platformText
        let carrier = imageLabel(cell("RVettore", row))
        let category = imageLabel(cell("RCategoria", row)).replacingOccurrences(of: "Categoria ", with: "")
        return RfiDeparture(
            service: TrainService(
                id: "rfi:\(number):\(Int(scheduled.timeIntervalSince1970))", number: number,
                operatorName: carrier.isEmpty ? "RFI" : carrier, category: category,
                destination: destination, scheduledDeparture: displayTime(scheduled), expectedDeparture: displayTime(expected),
                status: cancelled ? "CANCELLED" : (delay > 0 ? "DELAYED" : "ON_TIME"), platform: platform, delayMinutes: delay
            ), scheduled: scheduled, expected: expected, stopNames: stopNames
        )
    }

    private static func displayTime(_ date: Date) -> String {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }

    private static func imageLabel(_ html: String) -> String {
        captures(#"\balt=["']([^"']*)["']"#, html).first.map { text($0[1]) } ?? ""
    }

    private static func cell(_ id: String, _ html: String) -> String {
        captures("<td\\b[^>]*\\bid=[\"']\(id)[\"'][^>]*>([\\s\\S]*?)</td>", html).first?[1] ?? ""
    }

    private static func captures(_ pattern: String, _ value: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return [] }
        let source = value as NSString
        return regex.matches(in: value, range: NSRange(location: 0, length: source.length)).map { match in
            (0..<match.numberOfRanges).map { index in
                let range = match.range(at: index)
                return range.location == NSNotFound ? "" : source.substring(with: range)
            }
        }
    }

    private static func text(_ html: String) -> String {
        var value = html.replacingOccurrences(of: "<[^>]*>", with: " ", options: .regularExpression)
        for (entity, replacement) in [("&nbsp;", " "), ("&amp;", "&"), ("&quot;", "\""), ("&#39;", "'"), ("&apos;", "'"), ("&lt;", "<"), ("&gt;", ">") ] {
            value = value.replacingOccurrences(of: entity, with: replacement)
        }
        return value.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}
