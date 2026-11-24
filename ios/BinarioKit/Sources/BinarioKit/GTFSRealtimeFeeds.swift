import Foundation

// The open Trip Updates feeds of the bundled railway packs (Android's `StaRealtimeAdapter`,
// `SncfGtfsRealtimeAdapter`, `TrenitaliaFranceGtfsRealtimeAdapter`, `RealtimeFeedGuard`, `GtfsRealtimeFreshness`):
// the same addresses, limits, caches and pauses, from the shared table (`SharedData.RealtimeFeeds`).

/// One stop of a Trip Update as Android normalises it (`RealtimeStopUpdate`): only SCHEDULED trips and stops, each
/// with a stop_id or a stop_sequence.
public struct GTFSRealtimeStopUpdate: Equatable, Sendable {
    /// The feed's source identifier ("sncf-gtfs-rt").
    public let feedID: String
    /// The entity's id, else the trip_id (Android's `SourceRef.nativeId`).
    public let entityID: String
    public let tripID: String
    public let stopID: String?
    public let stopSequence: Int?
    /// TripDescriptor.start_date as yyyymmdd, when the feed gives one: an update for another operating day never
    /// joins a run.
    public let serviceDate: Int?
    /// The event's absolute time (StopTimeEvent.time), a prediction, never an observed passage.
    public let predicted: Date?
    /// StopTimeEvent.delay in seconds.
    public let delaySeconds: Int?
    /// The trip's timestamp, else the feed header's, else when the feed was asked.
    public let observedAt: Date
    /// `observedAt` plus the observation window (`GtfsRealtimeFreshness.OBSERVATION_WINDOW`).
    public let expiresAt: Date

    /// Android's `GtfsRealtimeEnrichedTrainDataSource.fresh`: observed within the window before `now`, at most a
    /// minute after it, and not expired.
    public func isFresh(at now: Date) -> Bool {
        observedAt >= now.addingTimeInterval(-SharedData.RealtimeFeeds.observationWindowSeconds)
            && observedAt <= now.addingTimeInterval(SharedData.RealtimeFeeds.futureToleranceSeconds)
            && expiresAt > now
    }
}

/// The two readings of a Trip Updates payload: the protobuf feeds (SNCF, Trenitalia France) and the Open Data Hub's
/// JSON rendering (STA). Pure functions; nil is an unreadable payload, as Android's parse error.
enum GTFSRealtimeStopUpdates {
    /// Java's `Instant` ends here: a feed with a timestamp past it fails to parse on Android.
    static let maximumInstantSecond: Int64 = 31_556_889_864_403_199
    /// A stop time is put in a time zone (`atZone`), whose local date-time ends earlier.
    static let maximumZonedSecond: Int64 = 31_556_889_832_773_599
    static let minimumZonedSecond: Int64 = -31_557_014_135_596_800

    /// Android's `gtfsRealtimeStopUpdates`: SCHEDULED trips with a trip_id, a readable start_date when one is given,
    /// SCHEDULED stops with a stop_id or a stop_sequence; the departure event first, else the arrival. Every trip is
    /// checked, the updates are made for `trips` only (nil: every trip). A time no calendar holds (past Java's last
    /// zoned date-time) is no prediction; Android refuses the whole feed for it.
    static func fromProtobuf(
        _ feed: GTFSRealtimeFeed, feedID: String, fetchedAt: Date, trips: Set<String>? = nil
    ) -> [GTFSRealtimeStopUpdate]? {
        guard let observed = instant(feed.timestamp, fallback: fetchedAt) else { return nil }
        var result: [GTFSRealtimeStopUpdate] = []
        for entity in feed.entities {
            guard let update = entity.tripUpdate else { continue }
            let tripID = update.trip.tripID
            guard !isBlank(tripID), update.trip.scheduleRelationship == .scheduled else { continue }
            let dateText = update.trip.startDate
            let serviceDate = basicISODate(dateText)
            if !isBlank(dateText), serviceDate == nil { continue }
            guard let tripObserved = instant(update.timestamp, fallback: observed) else { return nil }
            guard trips?.contains(tripID) ?? true else { continue }
            let entityID = isBlank(entity.id) ? tripID : entity.id
            for stop in update.stopTimeUpdates {
                guard stop.scheduleRelationship == .scheduled else { continue }
                let stopID: String? = isBlank(stop.stopID) ? nil : stop.stopID
                let sequence = stop.stopSequence.map { Int($0) }
                guard stopID != nil || sequence != nil else { continue }
                let event = stop.departure ?? stop.arrival
                result.append(GTFSRealtimeStopUpdate(
                    feedID: feedID, entityID: entityID, tripID: tripID, stopID: stopID, stopSequence: sequence,
                    serviceDate: serviceDate, predicted: zoned(event?.time), delaySeconds: event?.delay.map { Int($0) },
                    observedAt: tripObserved,
                    expiresAt: tripObserved.addingTimeInterval(SharedData.RealtimeFeeds.observationWindowSeconds)
                ))
            }
        }
        return result
    }

    /// `StaRealtimeAdapter.parseTripUpdates`: the same rule on the JSON rendering, where a relationship is
    /// "SCHEDULED" or "0" (absent counts as SCHEDULED), numbers may come as text and an event time counts when
    /// positive. Nil when the root is not an object.
    static func fromJSON(_ data: Data, feedID: String, fetchedAt: Date, trips: Set<String>? = nil) -> [GTFSRealtimeStopUpdate]? {
        guard !data.isEmpty,
              let root = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any] else { return nil }
        let header = root["header"] as? [String: Any]
        guard let observed = instant(long(header?["timestamp"]), fallback: fetchedAt) else { return nil }
        var result: [GTFSRealtimeStopUpdate] = []
        for item in root["entity"] as? [Any] ?? [] {
            guard let entity = item as? [String: Any], let update = entity["trip_update"] as? [String: Any],
                  let trip = update["trip"] as? [String: Any], let tripID = text(trip["trip_id"]),
                  isScheduled(trip["schedule_relationship"]) else { continue }
            let dateText = text(trip["start_date"])
            let serviceDate = dateText.flatMap(basicISODate)
            if dateText != nil, serviceDate == nil { continue }
            guard let tripObserved = instant(long(update["timestamp"]), fallback: observed) else { return nil }
            guard trips?.contains(tripID) ?? true else { continue }
            let entityID = text(entity["id"]) ?? tripID
            for element in update["stop_time_update"] as? [Any] ?? [] {
                guard let stop = element as? [String: Any], isScheduled(stop["schedule_relationship"]) else { continue }
                let stopID = text(stop["stop_id"])
                let sequence = int(stop["stop_sequence"]).flatMap { $0 >= 0 ? $0 : nil }
                guard stopID != nil || sequence != nil else { continue }
                let event = (stop["departure"] as? [String: Any]) ?? (stop["arrival"] as? [String: Any])
                let time = long(event?["time"]).flatMap { $0 > 0 ? $0 : nil }
                result.append(GTFSRealtimeStopUpdate(
                    feedID: feedID, entityID: entityID, tripID: tripID, stopID: stopID, stopSequence: sequence,
                    serviceDate: serviceDate, predicted: zoned(time), delaySeconds: long(event?["delay"]).map { Int($0) },
                    observedAt: tripObserved,
                    expiresAt: tripObserved.addingTimeInterval(SharedData.RealtimeFeeds.observationWindowSeconds)
                ))
            }
        }
        return result
    }

    /// `seconds` as an instant when positive, else `fallback`; nil past Java's last instant (a parse error there).
    private static func instant(_ seconds: Int64?, fallback: Date) -> Date? {
        guard let seconds, seconds > 0 else { return fallback }
        guard seconds <= maximumInstantSecond else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }

    /// An event time as an instant, nil when absent or past the zoned date-times Java can hold.
    private static func zoned(_ seconds: Int64?) -> Date? {
        guard let seconds, (minimumZonedSecond...maximumZonedSecond).contains(seconds) else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }

    /// `DateTimeFormatter.BASIC_ISO_DATE`: yyyyMMdd with an optional offset ("Z", ±HHMM, ±HHMMss), as yyyymmdd.
    static func basicISODate(_ value: String) -> Int? {
        let scalars = Array(value.unicodeScalars)
        guard scalars.count >= 8 else { return nil }
        var digits = 0
        for scalar in scalars.prefix(8) {
            guard ("0"..."9").contains(scalar) else { return nil }
            digits = digits * 10 + Int(scalar.value - 48)
        }
        let offset = String(String.UnicodeScalarView(scalars.dropFirst(8)))
        guard offset.isEmpty || offset == "Z" || isOffset(offset) else { return nil }
        let year = digits / 10_000, month = digits / 100 % 100, day = digits % 100
        guard (1...12).contains(month), day >= 1, day <= daysIn(month: month, year: year) else { return nil }
        return digits
    }

    private static func isOffset(_ value: String) -> Bool {
        let scalars = Array(value.unicodeScalars)
        guard scalars.count == 5 || scalars.count == 7, scalars[0] == "+" || scalars[0] == "-",
              scalars.dropFirst().allSatisfy({ ("0"..."9").contains($0) }) else { return false }
        let numbers = scalars.dropFirst().map { Int($0.value - 48) }
        let hours = numbers[0] * 10 + numbers[1], minutes = numbers[2] * 10 + numbers[3]
        let seconds = numbers.count == 6 ? numbers[4] * 10 + numbers[5] : 0
        return hours <= 18 && minutes <= 59 && seconds <= 59 && (hours < 18 || minutes + seconds == 0)
    }

    private static func daysIn(month: Int, year: Int) -> Int {
        switch month {
        case 2: return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// Kotlin's `isBlank`.
    private static func isBlank(_ value: String) -> Bool {
        value.unicodeScalars.allSatisfy { $0.properties.isWhitespace }
    }

    private static func isScheduled(_ value: Any?) -> Bool {
        let relationship = text(value) ?? "SCHEDULED"
        return relationship == "SCHEDULED" || relationship == "0"
    }

    /// Android's `textOr`: a value's text, untrimmed; nil when missing, JSON null or blank.
    private static func text(_ value: Any?) -> String? {
        let raw: String
        switch value {
        case let string as String: raw = string
        case let number as NSNumber: raw = number.stringValue
        default: return nil
        }
        return isBlank(raw) ? nil : raw
    }

    /// org.json's `optLong`: a number truncated towards zero, or the number a text holds; nil otherwise.
    private static func long(_ value: Any?) -> Int64? {
        let number: Double
        switch value {
        case let string as String:
            guard let parsed = Double(string.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
            number = parsed
        case let value as NSNumber:
            let double = value.doubleValue
            // A whole number is read exactly, a fraction truncated.
            if double.isFinite, double.rounded(.towardZero) == double, abs(double) < 9.2e18 { return value.int64Value }
            number = double
        default:
            return nil
        }
        guard number.isFinite else { return nil }
        if number >= 9.2e18 { return Int64.max }
        if number <= -9.2e18 { return Int64.min }
        return Int64(number.rounded(.towardZero))
    }

    private static func int(_ value: Any?) -> Int? {
        long(value).map { Int(Int32(clamping: $0)) }
    }

}

/// What a feed gave: its stop updates for the trips asked about, or why there are none.
enum GTFSRealtimeFeedRead: Equatable, Sendable {
    case updates([GTFSRealtimeStopUpdate])
    case unavailable(LiveUnavailableReason)
}

/// One Trip Updates feed of the shared table, asked as Android's adapter asks it:
/// - the payload is reused for the feed's time to live from the request (90 seconds for STA, two minutes for the
///   others), and a failure is answered from memory for `failureMemorySeconds` (Android's `RealtimeFeedGuard`);
/// - `gate` sends one request at a time, pauses at the first refusal and after repeated failures
///   (`SharedData.SourceManners`); the feeds of one host share it (`refusalGroup`: SNCF and Trenitalia France on
///   transport.data.gouv.fr), so they stop together, breaker included;
/// - an unreadable payload counts as a failure of the source.
/// The payload is kept as received and read again for each question, for the trips asked about only: a national feed
/// is never held as decoded objects.
final class GTFSRealtimeFeedSource: @unchecked Sendable {
    let feed: SharedData.RealtimeFeeds.TripUpdatesFeed
    let source: LiveSource
    let policy: LiveRequestPolicy
    private let client: LiveSourceClient
    private let gate: LiveSourceGate
    private let lock = NSLock()
    private var payload: Payload?
    private var failure: (reason: LiveUnavailableReason, until: Date)?

    private struct Payload {
        let data: Data
        let fetchedAt: Date
        let expiresAt: Date
    }

    init?(feed: SharedData.RealtimeFeeds.TripUpdatesFeed, client: LiveSourceClient, gate: LiveSourceGate) {
        guard let source = LiveSource(rawValue: feed.id) else { return nil }
        self.feed = feed
        self.source = source
        self.client = client
        self.gate = gate
        policy = LiveRequestPolicy(
            accept: feed.accept, requestTimeout: feed.readTimeoutSeconds, totalTimeout: feed.totalTimeoutSeconds,
            maximumBytes: feed.maximumBytes, maximumRequests: feed.maximumRequests,
            allowsSameHostRedirects: feed.redirects == "same-https-host"
        )
    }

    /// The gate of a feed: its pause is named after `source`; nothing is cached by it, the source keeps its payload.
    static func gate(source: LiveSource?, pauses: LiveSourcePauses, now: @escaping @Sendable () -> Date) -> LiveSourceGate {
        LiveSourceGate(source: source, configuration: .init(minimumInterval: 1, maximumEntries: 4), pauses: pauses, now: now)
    }

    /// The stop updates of `trips`, from the payload still valid or a new request.
    func updates(trips: Set<String>) async -> GTFSRealtimeFeedRead {
        let now = client.now()
        if let kept = validPayload(at: now) { return decode(kept, trips: trips) }
        if let remembered = rememberedFailure(at: now) { return .unavailable(remembered) }
        let address = feed.url
        guard let url = URL(string: address) else { return .unavailable(.invalidRequest) }
        let result = await gate.fetch(address) { [self] in await self.load(url, startedAt: now) }
        switch result {
        case .body:
            guard let kept = latestPayload() else { return .unavailable(.sourceUnavailable) }
            return decode(kept, trips: trips)
        case let .failure(failure):
            remember(failure, at: client.now())
            return .unavailable(failure.unavailableReason)
        }
    }

    private func load(_ url: URL, startedAt: Date) async -> LiveFetchResult {
        switch await client.getData(url, policy: policy) {
        case let .failure(failure):
            return .failure(failure)
        case let .body(data):
            // Read once whole: a payload Android could not parse is a failure of the source, never data.
            guard Self.readable(data, format: feed.format, feedID: feed.id, fetchedAt: startedAt) else { return .failure(.network) }
            store(Payload(data: data, fetchedAt: startedAt, expiresAt: startedAt.addingTimeInterval(feed.timeToLiveSeconds)))
            return .body(LiveBody(text: "", fetchedAt: startedAt))
        }
    }

    static func readable(_ data: Data, format: String, feedID: String, fetchedAt: Date) -> Bool {
        stopUpdates(data, format: format, feedID: feedID, fetchedAt: fetchedAt, trips: []) != nil
    }

    /// The stop updates of a payload, for `trips` only (nil: every trip); nil when it is unreadable.
    static func stopUpdates(_ data: Data, format: String, feedID: String, fetchedAt: Date, trips: Set<String>?) -> [GTFSRealtimeStopUpdate]? {
        if format == "json" { return GTFSRealtimeStopUpdates.fromJSON(data, feedID: feedID, fetchedAt: fetchedAt, trips: trips) }
        guard let decoded = GTFSRealtimeDecoder.decode(data, keepTrip: { trips?.contains($0) ?? true }) else { return nil }
        return GTFSRealtimeStopUpdates.fromProtobuf(decoded, feedID: feedID, fetchedAt: fetchedAt, trips: trips)
    }

    private func decode(_ kept: Payload, trips: Set<String>) -> GTFSRealtimeFeedRead {
        guard let updates = Self.stopUpdates(kept.data, format: feed.format, feedID: feed.id, fetchedAt: kept.fetchedAt, trips: trips) else {
            return .unavailable(.unreadable)
        }
        return .updates(updates)
    }

    private func validPayload(at now: Date) -> Payload? {
        lock.lock()
        defer { lock.unlock() }
        guard let payload, now < payload.expiresAt else { return nil }
        return payload
    }

    private func latestPayload() -> Payload? {
        lock.lock()
        defer { lock.unlock() }
        return payload
    }

    private func store(_ value: Payload) {
        lock.lock()
        payload = value
        failure = nil
        lock.unlock()
    }

    private func rememberedFailure(at now: Date) -> LiveUnavailableReason? {
        lock.lock()
        defer { lock.unlock() }
        guard let failure, now < failure.until else { return nil }
        return failure.reason
    }

    /// Android's `RealtimeFeedGuard.failed`: the failure is the answer for a while. A pause, a cancellation and a
    /// device without a connection asked nothing of the source and are not remembered.
    private func remember(_ failure: LiveFetchFailure, at now: Date) {
        switch failure {
        case .cancelled, .paused, .offline, .invalidRequest: return
        default: break
        }
        lock.lock()
        self.failure = (failure.unavailableReason, now.addingTimeInterval(SharedData.RealtimeFeeds.failureMemorySeconds))
        lock.unlock()
    }
}
