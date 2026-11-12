import Foundation

/// A source that is not asked again before `until`.
public struct LiveSourcePause: Equatable, Sendable {
    public let source: LiveSource
    public let until: Date
}

/// The paused sources, for a data status line («ViaggiaTreno in pausa fino alle 10:42»), as
/// Android's `SourcePauses`: process memory only.
public final class LiveSourcePauses: @unchecked Sendable {
    public static let shared = LiveSourcePauses()

    private let lock = NSLock()
    private var pauses: [LiveSource: Date] = [:]
    /// The gates sharing these pauses, for `cancelRequestsNobodyWaitsFor`.
    private var gates: [WeakGate] = []

    private struct WeakGate {
        weak var gate: LiveSourceGate?
    }

    public init() {}

    func register(_ gate: LiveSourceGate) {
        lock.lock()
        gates.removeAll { $0.gate == nil }
        gates.append(WeakGate(gate: gate))
        lock.unlock()
    }

    /// The app goes to the background or live data is switched off: every request on its way that
    /// no screen waits for any more is cancelled (`LiveSourceGate.cancelRequestsNobodyWaitsFor`).
    public func cancelRequestsNobodyWaitsFor() {
        lock.lock()
        let current = gates.compactMap(\.gate)
        lock.unlock()
        for gate in current { gate.cancelRequestsNobodyWaitsFor() }
    }

    func pause(_ source: LiveSource, until: Date) {
        lock.lock()
        pauses[source] = max(pauses[source] ?? until, until)
        lock.unlock()
    }

    func resume(_ source: LiveSource) {
        lock.lock()
        pauses[source] = nil
        lock.unlock()
    }

    /// The paused sources and when each will be asked again, earliest first; expired pauses are dropped.
    public func active(at now: Date) -> [LiveSourcePause] {
        lock.lock()
        pauses = pauses.filter { $0.value > now }
        let active = pauses.map { LiveSourcePause(source: $0.key, until: $0.value) }
        lock.unlock()
        return active.sorted { $0.until == $1.until ? $0.source.rawValue < $1.source.rawValue : $0.until < $1.until }
    }
}

/// The manners of one source, Android's `BoundedSingleFlightCache`, `SourceCircuitBreaker` and
/// `ResilientSourceCache` together:
/// - one request per resource and interval: concurrent callers share it, and its answer, failure
///   included, is reused until the interval has passed since the request started;
/// - a refusal (HTTP 403 or 429) pauses the whole source at once, for the `Retry-After` it sent
///   (at most an hour) or for 15 minutes, as Android's `PublicSourceRefusalGate`;
/// - three failures in a row pause it for a minute that doubles with every new pause, up to 15
///   minutes (a device without a connection is not the source's failure and never counts); after
///   a pause one request is let through (the
///   others are refused as during the pause until it answers) and a single failure pauses it again;
/// - only an answer to a request sent since the last pause ends it: one sent before says nothing of
///   the source after its refusal;
/// - nothing is retried by itself: the next caller after the interval asks again.
/// A caller that is cancelled returns at once, and a caller already cancelled sends nothing. A
/// request its callers left goes on to its own time limits and its answer is kept like any other,
/// so a screen that asks again at once (back in the foreground, a new search, a tab shown again)
/// joins it or reuses its answer instead of sending the same request twice; only
/// `cancelRequestsNobodyWaitsFor` (the app in the background, live data switched off) cancels it.
final class LiveSourceGate: @unchecked Sendable {
    struct Configuration: Equatable, Sendable {
        var minimumInterval: TimeInterval
        var maximumEntries = 64
        var failureThreshold = 3
        var baseOpenDuration: TimeInterval = 60
        var maximumOpenDuration: TimeInterval = 900
        /// The pause after a refusal without `Retry-After`, Android's `refusalPause`.
        var refusalPause: TimeInterval = 900
        var maximumRetryAfter: TimeInterval = 3_600
    }

    let source: LiveSource
    private let configuration: Configuration
    private let pauses: LiveSourcePauses
    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private var cache: [String: CachedResult] = [:]
    private var cacheOrder: [String] = []
    private var activeFlights: [String: UInt64] = [:]
    private var flights: [UInt64: Flight] = [:]
    private var nextIdentifier: UInt64 = 0
    private var consecutiveFailures = 0
    private var openings = 0
    private var openUntil: Date?
    /// Bumped by every pause: the requests sent before it cannot end it.
    private var pauseGeneration: UInt64 = 0
    /// The one request let through after a pause, while it has not answered.
    private var probe: UInt64?

    private struct CachedResult {
        let result: LiveFetchResult
        let expiresAt: Date
    }

    private final class Flight {
        let key: String
        let startedAt: Date
        /// The pause generation when the request was sent.
        let generation: UInt64
        var task: Task<Void, Never>?
        var abandoned = false
        var result: LiveFetchResult?
        /// Callers given this flight that have not started waiting yet.
        var registered: Set<UInt64> = []
        /// Callers cancelled before they started waiting.
        var cancelledEarly: Set<UInt64> = []
        var waiting: [UInt64: CheckedContinuation<LiveFetchResult, Never>] = [:]

        init(key: String, startedAt: Date, generation: UInt64) {
            self.key = key
            self.startedAt = startedAt
            self.generation = generation
        }

        var isSettled: Bool { registered.isEmpty && cancelledEarly.isEmpty && waiting.isEmpty }
    }

    private enum Ticket {
        case ready(LiveFetchResult)
        case wait(flight: UInt64, caller: UInt64)
        case start(flight: UInt64, caller: UInt64)
    }

    init(source: LiveSource, configuration: Configuration, pauses: LiveSourcePauses, now: @escaping @Sendable () -> Date) {
        self.source = source
        self.configuration = configuration
        self.pauses = pauses
        self.now = now
        pauses.register(self)
    }

    /// The answer for `key`: cached, shared with a request in flight, refused while the source is
    /// paused, or loaded now with `load`.
    func fetch(_ key: String, load: @escaping @Sendable () async -> LiveFetchResult) async -> LiveFetchResult {
        // A caller already cancelled (a screen gone, the app in the background) sends nothing.
        if Task.isCancelled { return .failure(.cancelled) }
        let flight: UInt64
        let caller: UInt64
        switch enter(key) {
        case let .ready(result):
            return result
        case let .wait(flightID, callerID):
            flight = flightID
            caller = callerID
        case let .start(flightID, callerID):
            flight = flightID
            caller = callerID
            let task = Task { [self] in
                let result = await load()
                self.complete(flight: flightID, with: result)
            }
            attach(task, to: flightID)
        }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<LiveFetchResult, Never>) in
                self.wait(flight: flight, caller: caller, continuation: continuation)
            }
        } onCancel: {
            self.leave(flight: flight, caller: caller)
        }
    }

    /// When the source is paused, the end of the pause.
    func pausedUntil() -> Date? {
        lock.lock()
        defer { lock.unlock() }
        guard let until = openUntil, now() < until else { return nil }
        return until
    }

    private func enter(_ key: String) -> Ticket {
        lock.lock()
        defer { lock.unlock() }
        let current = now()
        purgeExpired(at: current)
        if let cached = cache[key] { return .ready(cached.result) }
        var probing = false
        if let until = openUntil {
            if current < until { return .ready(.failure(.paused(until: until))) }
            // Half-open: one request goes through (callers of the same resource share it) and the
            // others are refused as during the pause until it answers; one more failure pauses the
            // source again.
            if let running = probe {
                guard activeFlights[key] == running else { return .ready(.failure(.paused(until: until))) }
            } else {
                probing = true
            }
        }
        nextIdentifier += 1
        let caller = nextIdentifier
        if let active = activeFlights[key], let flight = flights[active] {
            flight.registered.insert(caller)
            return .wait(flight: active, caller: caller)
        }
        nextIdentifier += 1
        let identifier = nextIdentifier
        let flight = Flight(key: key, startedAt: current, generation: pauseGeneration)
        flight.registered.insert(caller)
        flights[identifier] = flight
        activeFlights[key] = identifier
        if probing {
            probe = identifier
            consecutiveFailures = max(0, configuration.failureThreshold - 1)
        }
        return .start(flight: identifier, caller: caller)
    }

    private func attach(_ task: Task<Void, Never>, to identifier: UInt64) {
        lock.lock()
        let abandoned = flights[identifier]?.abandoned ?? true
        if !abandoned { flights[identifier]?.task = task }
        lock.unlock()
        if abandoned { task.cancel() }
    }

    private func wait(flight identifier: UInt64, caller: UInt64, continuation: CheckedContinuation<LiveFetchResult, Never>) {
        lock.lock()
        guard let flight = flights[identifier] else {
            lock.unlock()
            continuation.resume(returning: .failure(.cancelled))
            return
        }
        if flight.cancelledEarly.remove(caller) != nil {
            settle(identifier)
            lock.unlock()
            continuation.resume(returning: .failure(.cancelled))
            return
        }
        flight.registered.remove(caller)
        if let result = flight.result {
            settle(identifier)
            lock.unlock()
            continuation.resume(returning: result)
            return
        }
        flight.waiting[caller] = continuation
        lock.unlock()
    }

    private func leave(flight identifier: UInt64, caller: UInt64) {
        lock.lock()
        guard let flight = flights[identifier] else {
            lock.unlock()
            return
        }
        let continuation = flight.waiting.removeValue(forKey: caller)
        if continuation == nil, flight.registered.remove(caller) != nil {
            flight.cancelledEarly.insert(caller)
        }
        // Nobody may wait for the request any more: it still goes on, so that the next caller of
        // the same resource joins it or reuses its answer rather than sending it again.
        if flight.result != nil || flight.abandoned { settle(identifier) }
        lock.unlock()
        continuation?.resume(returning: .failure(.cancelled))
    }

    /// Cancels every request nobody waits for, and the next caller starts afresh: the app goes to
    /// the background or live data is switched off. A request a caller still waits for goes on.
    func cancelRequestsNobodyWaitsFor() {
        lock.lock()
        var tasks: [Task<Void, Never>] = []
        let unwaited = flights.filter {
            $0.value.result == nil && !$0.value.abandoned && $0.value.waiting.isEmpty && $0.value.registered.isEmpty
        }
        for (identifier, flight) in unwaited {
            flight.abandoned = true
            if activeFlights[flight.key] == identifier { activeFlights[flight.key] = nil }
            // An abandoned probe lets the next caller through instead.
            if probe == identifier { probe = nil }
            if let task = flight.task { tasks.append(task) }
            settle(identifier)
        }
        lock.unlock()
        for task in tasks { task.cancel() }
    }

    private func complete(flight identifier: UInt64, with result: LiveFetchResult) {
        lock.lock()
        let current = now()
        var pauseUntil: Date?
        var resumed = false
        var continuations: [CheckedContinuation<LiveFetchResult, Never>] = []
        var sentSinceLastPause = false
        if probe == identifier { probe = nil }
        if let flight = flights[identifier] {
            sentSinceLastPause = flight.generation == pauseGeneration
            if activeFlights[flight.key] == identifier { activeFlights[flight.key] = nil }
            if Self.isCacheable(result) { store(result, for: flight.key, startedAt: flight.startedAt, at: current) }
            flight.result = result
            continuations = Array(flight.waiting.values)
            flight.waiting = [:]
            flight.task = nil
            settle(identifier)
        }
        switch result {
        case .body:
            // A request sent before the last pause answers for the source as it was then.
            guard sentSinceLastPause else { break }
            consecutiveFailures = 0
            openings = 0
            openUntil = nil
            resumed = true
        case let .failure(failure):
            pauseUntil = record(failure, at: current)
        }
        lock.unlock()
        if resumed { pauses.resume(source) }
        if let pauseUntil { pauses.pause(source, until: pauseUntil) }
        for continuation in continuations { continuation.resume(returning: result) }
    }

    /// Android's `SourceCircuitBreaker.recordFailure`, with a refusal pausing at once. The pause
    /// end, when the source is now paused.
    private func record(_ failure: LiveFetchFailure, at current: Date) -> Date? {
        switch failure {
        case .cancelled, .paused, .invalidRequest, .offline: return nil
        default: break
        }
        consecutiveFailures += 1
        if let asked = failure.retryAfter, asked > 0 {
            let pause = min(asked, configuration.maximumRetryAfter)
            openUntil = max(openUntil ?? current, current.addingTimeInterval(pause))
            openings += 1
            pauseGeneration &+= 1
            consecutiveFailures = 0
        } else if failure.isRefusal || consecutiveFailures >= configuration.failureThreshold {
            let factor: Double = Double(1 << min(openings, 6))
            let backOff: TimeInterval = min(configuration.baseOpenDuration * factor, configuration.maximumOpenDuration)
            // A refusal is never paused for less than Android pauses it, from the first one on.
            let duration: TimeInterval = failure.isRefusal ? max(backOff, configuration.refusalPause) : backOff
            openUntil = current.addingTimeInterval(duration)
            openings += 1
            pauseGeneration &+= 1
            consecutiveFailures = 0
        }
        guard let until = openUntil, current < until else { return nil }
        return until
    }

    private static func isCacheable(_ result: LiveFetchResult) -> Bool {
        switch result {
        case .body: return true
        case .failure(.cancelled), .failure(.paused), .failure(.invalidRequest): return false
        case .failure: return true
        }
    }

    private func store(_ result: LiveFetchResult, for key: String, startedAt: Date, at current: Date) {
        let expiresAt = startedAt.addingTimeInterval(configuration.minimumInterval)
        guard expiresAt > current else { return }
        if cache[key] == nil { cacheOrder.append(key) }
        cache[key] = CachedResult(result: result, expiresAt: expiresAt)
        while cacheOrder.count > configuration.maximumEntries {
            cache[cacheOrder.removeFirst()] = nil
        }
    }

    private func purgeExpired(at current: Date) {
        guard !cache.isEmpty else { return }
        cache = cache.filter { $0.value.expiresAt > current }
        cacheOrder.removeAll { cache[$0] == nil }
    }

    /// Forgets a flight nobody can still ask about.
    private func settle(_ identifier: UInt64) {
        guard let flight = flights[identifier], flight.result != nil || flight.abandoned, flight.isSettled else { return }
        flights[identifier] = nil
    }
}

/// Time limits and bounded parallel work for the live sources.
enum LiveTasks {
    private enum Outcome<Value: Sendable>: Sendable {
        case finished(Value)
        case expired
    }

    /// `operation`'s value, or nil when it takes longer than `seconds` (it is then cancelled).
    static func withDeadline<Value: Sendable>(
        seconds: TimeInterval, _ operation: @escaping @Sendable () async -> Value
    ) async -> Value? {
        let clamped: TimeInterval = min(max(seconds, 0), 3_600)
        let nanoseconds = UInt64(clamped * 1_000_000_000)
        return await withTaskGroup(of: Outcome<Value>.self) { group in
            group.addTask {
                let value = await operation()
                return Outcome.finished(value)
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: nanoseconds)
                return Outcome.expired
            }
            let first = await group.next()
            group.cancelAll()
            guard let first, case let .finished(value) = first else { return nil }
            return value
        }
    }

    /// `transform` of every input, at most `maximumConcurrent` at a time, in the order of `inputs`.
    static func boundedMap<Input: Sendable, Output: Sendable>(
        _ inputs: [Input], maximumConcurrent: Int, _ transform: @escaping @Sendable (Input) async -> Output
    ) async -> [Output] {
        guard !inputs.isEmpty else { return [] }
        return await withTaskGroup(of: (Int, Output).self) { group in
            var results: [Output?] = Array(repeating: nil, count: inputs.count)
            var next = 0
            let width = max(1, min(maximumConcurrent, inputs.count))
            while next < width {
                let index = next
                let input = inputs[index]
                group.addTask {
                    let output = await transform(input)
                    return (index, output)
                }
                next += 1
            }
            while let finished = await group.next() {
                results[finished.0] = finished.1
                if next < inputs.count, !Task.isCancelled {
                    let index = next
                    let input = inputs[index]
                    group.addTask {
                        let output = await transform(input)
                        return (index, output)
                    }
                    next += 1
                }
            }
            return results.compactMap { $0 }
        }
    }
}

/// Values gathered by parallel work that may run out of time: what finished is kept.
final class LiveCollector<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [(Int, Value)] = []
    private var failures: [LiveUnavailableReason] = []

    func append(_ value: Value, order: Int) {
        lock.lock()
        values.append((order, value))
        lock.unlock()
    }

    func fail(_ reason: LiveUnavailableReason) {
        lock.lock()
        failures.append(reason)
        lock.unlock()
    }

    /// The values in the order they were asked for, and the failures in the order they happened.
    func snapshot() -> (values: [Value], failures: [LiveUnavailableReason]) {
        lock.lock()
        defer { lock.unlock() }
        return (values.sorted { $0.0 < $1.0 }.map { $0.1 }, failures)
    }
}
