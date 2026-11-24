import Foundation

/// Android's `ResilientSourceCache` for sources asked by key (a place, a GBFS document): each answer
/// is kept for its time to live, then served as an earlier copy for at most `maximumStale` while a
/// refresh fails; at most `maximumEntries` keys, the oldest written dropped first. In memory only.
/// The requests themselves go through a `LiveSourceGate` (one per interval, pauses, breaker).
actor KeyedSourceCache<Value: Sendable> {
    private let timeToLive: TimeInterval
    private let maximumStale: TimeInterval
    private let maximumEntries: Int
    private var kept: [String: (value: Value, fetchedAt: Date)] = [:]
    private var order: [String] = []

    init(timeToLive: TimeInterval, maximumStale: TimeInterval, maximumEntries: Int) {
        self.timeToLive = timeToLive
        self.maximumStale = maximumStale
        self.maximumEntries = max(1, maximumEntries)
    }

    /// The answer for `key` and when it was fetched: kept, loaded now, or an earlier copy after a failure.
    func read(
        _ key: String, at now: Date, load: @escaping @Sendable () async -> NoticeFeedLoad<Value>
    ) async -> (read: NoticeFeedRead<Value>, fetchedAt: Date?) {
        if let entry = kept[key], now < entry.fetchedAt.addingTimeInterval(timeToLive) { return (.fresh(entry.value), entry.fetchedAt) }
        switch await load() {
        case let .loaded(value, fetchedAt):
            if kept[key].map({ fetchedAt >= $0.fetchedAt }) ?? true { store(value, fetchedAt: fetchedAt, for: key) }
            return (.fresh(value), fetchedAt)
        case let .failed(reason):
            if let entry = kept[key], now <= entry.fetchedAt.addingTimeInterval(timeToLive + maximumStale) {
                return (.stale(entry.value), entry.fetchedAt)
            }
            return (.failed(reason), nil)
        }
    }

    private func store(_ value: Value, fetchedAt: Date, for key: String) {
        if kept[key] == nil { order.append(key) }
        kept[key] = (value, fetchedAt)
        while order.count > maximumEntries {
            kept[order.removeFirst()] = nil
        }
    }
}

/// Distances on the ground, as Android's `GtfsStationCodeIndex.distanceKm` (haversine, mean radius
/// 6371 km), so that both apps round a distance to the same metre.
public enum GroundDistance {
    public static func kilometres(_ latitudeA: Double, _ longitudeA: Double, _ latitudeB: Double, _ longitudeB: Double) -> Double {
        let radians = Double.pi / 180
        let phi1 = latitudeA * radians
        let phi2 = latitudeB * radians
        let deltaPhi = phi2 - phi1
        let deltaLambda = (longitudeB - longitudeA) * radians
        let a = sin(deltaPhi / 2) * sin(deltaPhi / 2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * 6_371.0 * asin(min(max(a, 0), 1).squareRoot())
    }

    /// Whole metres, half away from zero (Java's `Math.round` for positive values).
    public static func metres(_ latitudeA: Double, _ longitudeA: Double, _ latitudeB: Double, _ longitudeB: Double) -> Int {
        Int((kilometres(latitudeA, longitudeA, latitudeB, longitudeB) * 1_000).rounded(.toNearestOrAwayFromZero))
    }
}

/// Values of untrusted JSON read as Android's org.json reads them: a boolean is never a number.
enum ForeignJSON {
    static func isBoolean(_ number: NSNumber) -> Bool {
        String(cString: number.objCType) == "c"
    }

    /// A JSON number (not a boolean), finite; nil otherwise (`(opt(key) as? Number)?.toDouble()`).
    static func number(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber, !isBoolean(number) else { return nil }
        let double = number.doubleValue
        return double.isFinite ? double : nil
    }

    /// `optDouble`: a number, or a string that reads as one.
    static func looseNumber(_ value: Any?) -> Double? {
        if let number = number(value) { return number }
        if let text = value as? String, let double = Double(text.trimmingCharacters(in: .whitespaces)), double.isFinite { return double }
        return nil
    }

    /// Android's `textOr`: the value as text, nil when missing, JSON null, a collection or blank.
    static func text(_ value: Any?) -> String? {
        let text: String
        switch value {
        case let string as String: text = string
        case let number as NSNumber: text = isBoolean(number) ? (number.boolValue ? "true" : "false") : number.stringValue
        default: return nil
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
    }

    /// Android's `flag`: a boolean, a number other than zero, else `fallback`.
    static func flag(_ value: Any?, fallback: Bool) -> Bool {
        guard let number = value as? NSNumber else { return fallback }
        return isBoolean(number) ? number.boolValue : number.doubleValue != 0
    }

    /// A number truncated towards zero as Kotlin's `toInt()`, nil beyond the 32-bit range (no value
    /// the readers accept is that large).
    static func whole(_ value: Double) -> Int? {
        guard value.isFinite, abs(value) < Double(Int32.max) else { return nil }
        return Int(value)
    }

    /// Android's `count`: a JSON number truncated to a whole one, not negative.
    static func count(_ value: Any?) -> Int? {
        guard let double = number(value), double >= 0, double < Double(Int32.max) else { return nil }
        return Int(double)
    }

    static func object(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8), !data.isEmpty else { return nil }
        return (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any]
    }

    static func objects(_ value: Any?) -> [[String: Any]] {
        (value as? [Any])?.compactMap { $0 as? [String: Any] } ?? []
    }
}
