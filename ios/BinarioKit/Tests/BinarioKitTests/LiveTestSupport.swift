import Foundation
import XCTest
@testable import BinarioKit

/// Recorded and synthetic payloads of the live sources (`Fixtures/`), read from the checkout as the
/// other BinarioKit tests read theirs: no network in any test.
enum LiveFixtures {
    static func text(_ name: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Fixtures")
            .appendingPathComponent(name)
        return try String(contentsOf: url, encoding: .utf8)
    }

    static func json(_ name: String) throws -> LiveJSONObject {
        try XCTUnwrap(LiveJSONObject(LiveJSON.parse(text(name))))
    }
}

/// A clock the tests move by hand.
final class LiveTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ start: Date) {
        current = start
    }

    func now() -> Date {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    func advance(_ seconds: TimeInterval) {
        lock.lock()
        current = current.addingTimeInterval(seconds)
        lock.unlock()
    }

    var closure: @Sendable () -> Date { { [self] in self.now() } }
}

/// A transport that answers from a handler and records every request; nothing leaves the machine.
final class FakeLiveTransport: LiveHTTPTransport, @unchecked Sendable {
    typealias Handler = @Sendable (LiveHTTPRequest) async -> LiveTransportOutcome

    private let lock = NSLock()
    private var recorded: [LiveHTTPRequest] = []
    private let handler: Handler

    init(_ handler: @escaping Handler) {
        self.handler = handler
    }

    func send(_ request: LiveHTTPRequest) async -> LiveTransportOutcome {
        record(request)
        return await handler(request)
    }

    private func record(_ request: LiveHTTPRequest) {
        lock.lock()
        recorded.append(request)
        lock.unlock()
    }

    var requests: [LiveHTTPRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    var paths: [String] { requests.map { $0.url.absoluteString } }

    static func ok(_ text: String, headers: [String: String] = [:]) -> LiveTransportOutcome {
        .response(status: 200, headers: headers, body: Data(text.utf8))
    }

    static func status(_ status: Int, headers: [String: String] = [:]) -> LiveTransportOutcome {
        .response(status: status, headers: headers, body: Data())
    }

    static func redirect(_ location: String, status: Int = 301) -> LiveTransportOutcome {
        .response(status: status, headers: ["location": location], body: Data())
    }

    /// Waits until the request is cancelled (or a minute passes), as a source that never answers.
    static func stall() async -> LiveTransportOutcome {
        try? await Task.sleep(nanoseconds: 60_000_000_000)
        return .failure(.cancelled)
    }
}

/// A counter shared with `@Sendable` closures.
final class LiveCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var flags: Set<String> = []

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func mark(_ flag: String) {
        lock.lock()
        flags.insert(flag)
        lock.unlock()
    }

    func marked(_ flag: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return flags.contains(flag)
    }
}

enum LiveTestDates {
    /// 2026-10-16 08:00:00 UTC, 10:00 in Rome: the clock of Android's ViaggiaTreno and Italo tests.
    static let viaggiaTrenoNow = Date(timeIntervalSince1970: 1_792_137_600)
    /// 2026-10-16 00:00 in Rome: the ViaggiaTreno date of those runs.
    static let runDateMillis: Int64 = 1_792_101_600_000

    static func rome(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int, _ second: Int = 0) -> Date {
        let components = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        return LiveRailTime.calendar.date(from: components) ?? Date(timeIntervalSince1970: 0)
    }
}
