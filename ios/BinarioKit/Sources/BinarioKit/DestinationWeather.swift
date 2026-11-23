import Foundation

/// The station and day a train detail's weather refers to: the last stop shown, on its arrival
/// date (Android's `DestinationWeatherTarget`). The cases are
/// contracts/shared-rules/v1/destination-weather-v1.json.
public struct DestinationWeatherTarget: Equatable, Sendable {
    public let stationName: String
    public let arrivalDate: PlannedLocalDate

    public init(stationName: String, arrivalDate: PlannedLocalDate) {
        self.stationName = stationName
        self.arrivalDate = arrivalDate
    }

    /// The last stop (else the destination) on the departure day, or the next day when its
    /// scheduled arrival clock time is earlier than the departure's: the train arrives after
    /// midnight. Without a departure date, today.
    public static func of(
        lastStop: String?, lastScheduled: String?, destination: String,
        departureDate: PlannedLocalDate?, departure: String?, today: PlannedLocalDate
    ) -> DestinationWeatherTarget? {
        let station = lastStop.map(trimmed).flatMap { $0.isEmpty ? nil : $0 } ?? trimmed(destination)
        guard !station.isEmpty else { return nil }
        let day = departureDate ?? today
        let departureTime = clock(departure)
        let arrivalTime = clock(lastScheduled)
        var date = day
        if let arrivalTime, let departureTime, arrivalTime < departureTime, let next = day.adding(days: 1) { date = next }
        return DestinationWeatherTarget(stationName: station, arrivalDate: date)
    }

    /// The target of a run the live sources published: its last stop and scheduled arrival, read
    /// on the Italian wall clock.
    public static func of(_ run: LiveRunStatus, today: PlannedLocalDate) -> DestinationWeatherTarget? {
        let last = run.stops.last
        return of(
            lastStop: last?.stationName,
            lastScheduled: last.flatMap { ItalianWallClock.time(of: $0.scheduled)?.hhmm },
            destination: run.destinationName,
            departureDate: ItalianWallClock.date(of: run.scheduledDeparture),
            departure: ItalianWallClock.time(of: run.scheduledDeparture)?.hhmm,
            today: today
        )
    }

    private static func trimmed(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Android's `parse`: «HH:mm», the minutes read from the first two characters after the colon.
    static func clock(_ value: String?) -> PlannedLocalTime? {
        guard let parts = value?.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":", omittingEmptySubsequences: false),
              parts.count >= 2, let hour = Int(parts[0]), let minute = Int(parts[1].prefix(2)) else { return nil }
        return PlannedLocalTime(hour: hour, minute: minute)
    }
}

/// One day of the Open-Meteo daily forecast; every value is the provider's, nil when it publishes none.
public struct DailyForecast: Equatable, Sendable {
    public let date: PlannedLocalDate
    /// WMO weather interpretation code, 0–99.
    public let weatherCode: Int?
    public let maxTemperatureC: Double?
    public let minTemperatureC: Double?
    public let precipitationProbabilityPercent: Int?

    public init(date: PlannedLocalDate, weatherCode: Int?, maxTemperatureC: Double?, minTemperatureC: Double?, precipitationProbabilityPercent: Int?) {
        self.date = date
        self.weatherCode = weatherCode
        self.maxTemperatureC = maxTemperatureC
        self.minTemperatureC = minTemperatureC
        self.precipitationProbabilityPercent = precipitationProbabilityPercent
    }
}

/// What the weather section shows (Android's `DestinationWeatherResult`).
public enum DestinationWeatherResult: Equatable, Sendable {
    case forecast(DailyForecast, stale: Bool)
    /// The arrival day is outside the forecast asked for (today and tomorrow).
    case outOfRange
    case unavailable
}

/// Daily forecast for a station from Open-Meteo (CC BY 4.0, free for non-commercial use, no key),
/// Android's `OpenMeteoForecastAdapter`. Only the station's catalogue coordinates, rounded to two
/// decimals (about 1 km), are sent; never the user's location. Each answer is kept per rounded place
/// and day for three hours, and the requests go through a `LiveSourceGate` (one per interval, a pause
/// at the first refusal, the breaker of every source). The source is
/// contracts/shared-data/v1/destination-weather-v1.json.
public final class DestinationWeatherService: Sendable {
    public static let attributionURL = URL(string: SharedData.DestinationWeather.attributionUrl)
    public static let attributionHost = "open-meteo.com"

    private let client: LiveSourceClient
    private let gate: LiveSourceGate
    private let cache: KeyedSourceCache<[DailyForecast]>
    private let now: @Sendable () -> Date

    init(transport: any LiveHTTPTransport, pauses: LiveSourcePauses, now: @escaping @Sendable () -> Date) {
        client = LiveSourceClient(transport: transport, now: now)
        gate = LiveSourceGate(
            source: nil,
            configuration: .init(minimumInterval: SharedData.DestinationWeather.minimumIntervalSeconds,
                                 maximumEntries: SharedData.DestinationWeather.maximumEntries),
            pauses: pauses, now: now
        )
        cache = KeyedSourceCache(
            timeToLive: SharedData.DestinationWeather.timeToLiveSeconds,
            maximumStale: SharedData.DestinationWeather.maximumStaleSeconds,
            maximumEntries: SharedData.DestinationWeather.maximumEntries
        )
        self.now = now
    }

    public static func standard(
        transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) -> DestinationWeatherService {
        DestinationWeatherService(transport: transport, pauses: pauses, now: now)
    }

    /// Two decimals, half away from zero, in a locale-independent form: the only place information
    /// sent to Open-Meteo (Android's `BigDecimal.setScale(2, HALF_UP)` of the shortest decimal form).
    public static func rounded(_ value: Double) -> String {
        let scale = SharedData.DestinationWeather.coordinateDecimals
        guard var decimal = Decimal(string: "\(value)", locale: Locale(identifier: "en_US_POSIX")) else { return "0" }
        var result = Decimal()
        NSDecimalRound(&result, &decimal, scale, .plain)
        var factor = Decimal(1)
        for _ in 0..<scale { factor *= 10 }
        let scaled = NSDecimalNumber(decimal: result * factor).int64Value
        let negative = scaled < 0 || (scaled == 0 && result < 0)
        let magnitude = scaled.magnitude
        let divisor = UInt64(truncating: NSDecimalNumber(decimal: factor))
        var text = String(magnitude / max(divisor, 1))
        if scale > 0 {
            let fraction = String(magnitude % max(divisor, 1))
            text += "." + String(repeating: "0", count: max(0, scale - fraction.count)) + fraction
        }
        return (negative ? "-" : "") + text
    }

    /// The request for a place, nil for coordinates out of range.
    public static func url(latitude: Double, longitude: Double) -> URL? {
        guard latitude.isFinite, longitude.isFinite, (-90.0...90.0).contains(latitude), (-180.0...180.0).contains(longitude) else { return nil }
        let table = SharedData.DestinationWeather.self
        let zone = table.timeZone.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? table.timeZone
        let address = table.forecastUrl + "?latitude=\(rounded(latitude))&longitude=\(rounded(longitude))"
            + "&daily=\(table.dailyVariables.joined(separator: ","))&timezone=\(zone)&forecast_days=\(table.forecastDays)"
        return URL(string: address)
    }

    /// Android's `OpenMeteoForecastAdapter.parse`: the days of `daily`, values outside their ranges
    /// dropped, at most 16 days; nil (unreadable) without `daily` or its dates.
    public static func parse(_ body: String) -> [DailyForecast]? {
        guard let root = ForeignJSON.object(body), let daily = root["daily"] as? [String: Any],
              let days = daily["time"] as? [Any] else { return nil }
        func series(_ key: String) -> [Any] { daily[key] as? [Any] ?? [] }
        let codes = series("weather_code"), maxima = series("temperature_2m_max")
        let minima = series("temperature_2m_min"), rain = series("precipitation_probability_max")
        func number(_ list: [Any], _ index: Int) -> Double? { index < list.count ? ForeignJSON.number(list[index]) : nil }
        var result: [DailyForecast] = []
        for index in 0..<min(days.count, SharedData.DestinationWeather.maximumDays) {
            guard let text = ForeignJSON.text(days[index]), let date = PlannedLocalDate(iso: text.trimmingCharacters(in: .whitespaces)) else { continue }
            let code = number(codes, index).flatMap(ForeignJSON.whole).flatMap { (0...99).contains($0) ? $0 : nil }
            let maximum = number(maxima, index).flatMap { (-90.0...60.0).contains($0) ? $0 : nil }
            let minimum = number(minima, index).flatMap { (-90.0...60.0).contains($0) ? $0 : nil }
            let probability = number(rain, index).flatMap(ForeignJSON.whole).flatMap { (0...100).contains($0) ? $0 : nil }
            result.append(DailyForecast(date: date, weatherCode: code, maxTemperatureC: maximum, minTemperatureC: minimum,
                                        precipitationProbabilityPercent: probability))
        }
        return result
    }

    /// The forecast of `date` at the station's catalogue coordinates. A day outside today and
    /// tomorrow is out of range and asks nothing.
    public func forecast(latitude: Double, longitude: Double, date: PlannedLocalDate) async -> DestinationWeatherResult {
        guard let today = ItalianWallClock.date(of: now()) else { return .unavailable }
        guard let last = today.adding(days: SharedData.DestinationWeather.forecastDays - 1), !(date < today), !(last < date) else {
            return .outOfRange
        }
        guard let url = Self.url(latitude: latitude, longitude: longitude) else { return .unavailable }
        let policy = LiveRequestPolicy(
            accept: "application/json",
            requestTimeout: SharedData.DestinationWeather.readTimeoutSeconds,
            totalTimeout: SharedData.DestinationWeather.totalTimeoutSeconds,
            maximumBytes: SharedData.DestinationWeather.maximumBytes
        )
        let client = self.client
        let gate = self.gate
        let key = "\(Self.rounded(latitude)),\(Self.rounded(longitude))@\(today.iso)"
        let read = await cache.read(key, at: now()) {
            switch await gate.fetch(url.absoluteString, load: { await client.get(url, policy: policy) }) {
            case let .failure(failure):
                return .failed(failure.unavailableReason)
            case let .body(body):
                guard let days = Self.parse(body.text) else { return .failed(.unreadable) }
                return .loaded(days, fetchedAt: body.fetchedAt)
            }
        }
        switch read.read {
        case let .fresh(days):
            return days.first { $0.date == date }.map { .forecast($0, stale: false) } ?? .outOfRange
        case let .stale(days):
            return days.first { $0.date == date }.map { .forecast($0, stale: true) } ?? .outOfRange
        case .failed:
            return .unavailable
        }
    }
}

/// Catalogue stations with coordinates, by normalised name and alias (Android's
/// `StationCoordinateIndex`): places a train's published station names, which carry no coordinates.
public struct StationCoordinateIndex: Sendable {
    private let byName: [String: Station]

    public init(_ stations: [Station]) {
        var byName: [String: Station] = [:]
        for station in stations where station.latitude != nil && station.longitude != nil {
            for name in [station.name] + station.aliases {
                let key = TrenordLineMatcher.normalizeStationName(name)
                if key.count >= 2, byName[key] == nil { byName[key] = station }
            }
        }
        self.byName = byName
    }

    public func find(_ name: String) -> Station? {
        byName[TrenordLineMatcher.normalizeStationName(name)]
    }
}

/// The WMO weather interpretation codes Open-Meteo publishes and the key of each one's text
/// (Android's `wmoDescription`, `weather_code_<n>`): «result.weather.code.<n>», else «result.weather.code.unknown».
public enum WeatherCodes {
    public static let described: Set<Int> = [
        0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99,
    ]

    public static func key(_ code: Int?) -> String {
        guard let code, described.contains(code) else { return "result.weather.code.unknown" }
        return "result.weather.code.\(code)"
    }
}
