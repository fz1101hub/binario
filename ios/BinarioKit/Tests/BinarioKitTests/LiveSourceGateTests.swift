import Foundation
import XCTest
@testable import BinarioKit

final class LiveSourceGateTests: XCTestCase {
    private func makeGate(_ clock: LiveTestClock, pauses: LiveSourcePauses = LiveSourcePauses(), interval: TimeInterval = 30) -> LiveSourceGate {
        LiveSourceGate(source: .viaggiaTreno, configuration: .init(minimumInterval: interval), pauses: pauses, now: clock.closure)
    }

    private func answered(_ text: String, _ clock: LiveTestClock) -> LiveFetchResult {
        .body(LiveBody(text: text, fetchedAt: clock.now()))
    }

    func testConcurrentCallersShareOneRequestAndTheAnswerIsReusedWithinTheInterval() async {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let gate = makeGate(clock)
        let loads = LiveCounter()
        let answer = answered("board", clock)
        let load: @Sendable () async -> LiveFetchResult = {
            loads.increment()
            try? await Task.sleep(nanoseconds: 300_000_000)
            return answer
        }
        let results: [LiveFetchResult] = await withTaskGroup(of: LiveFetchResult.self) { group in
            for _ in 0..<4 { group.addTask { await gate.fetch("partenze/S08328", load: load) } }
            var collected: [LiveFetchResult] = []
            for await result in group { collected.append(result) }
            return collected
        }
        XCTAssertEqual(results, Array(repeating: answer, count: 4))
        XCTAssertEqual(loads.value, 1)
        clock.advance(20)
        let cached = await gate.fetch("partenze/S08328", load: load)
        XCTAssertEqual(cached, answer)
        XCTAssertEqual(loads.value, 1, "within 30 seconds the resource is not asked again")
        clock.advance(11)
        _ = await gate.fetch("partenze/S08328", load: load)
        XCTAssertEqual(loads.value, 2)
    }

    func testAFailureIsRememberedForTheIntervalAndNeverRetriedByItself() async {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let gate = makeGate(clock)
        let loads = LiveCounter()
        let load: @Sendable () async -> LiveFetchResult = {
            loads.increment()
            return .failure(.network)
        }
        let first = await gate.fetch("andamentoTreno/S08001/12345/1", load: load)
        let second = await gate.fetch("andamentoTreno/S08001/12345/1", load: load)
        XCTAssertEqual(first, .failure(.network))
        XCTAssertEqual(second, .failure(.network))
        XCTAssertEqual(loads.value, 1, "one request, no automatic retry")
    }

    func testTheFirstRefusalPausesTheWholeSourceAtOnce() async {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let pauses = LiveSourcePauses()
        let gate = makeGate(clock, pauses: pauses)
        let loads = LiveCounter()
        let refused = await gate.fetch("cercaNumeroTrenoTrenoAutocomplete/1") {
            loads.increment()
            return .failure(.http(status: 429, retryAfter: nil))
        }
        XCTAssertEqual(refused, .failure(.http(status: 429, retryAfter: nil)))
        let until = LiveTestDates.viaggiaTrenoNow.addingTimeInterval(60)
        let next = await gate.fetch("cercaNumeroTrenoTrenoAutocomplete/2") {
            loads.increment()
            return .failure(.network)
        }
        XCTAssertEqual(next, .failure(.paused(until: until)))
        XCTAssertEqual(loads.value, 1, "nothing is sent while the source is paused")
        XCTAssertEqual(gate.pausedUntil(), until)
        XCTAssertEqual(pauses.active(at: clock.now()), [LiveSourcePause(source: .viaggiaTreno, until: until)])
        XCTAssertEqual(pauses.active(at: until), [], "an expired pause is dropped")

        // After the pause one request goes through; a new refusal pauses for twice as long.
        clock.advance(61)
        _ = await gate.fetch("cercaNumeroTrenoTrenoAutocomplete/3") {
            loads.increment()
            return .failure(.http(status: 403, retryAfter: nil))
        }
        XCTAssertEqual(loads.value, 2)
        XCTAssertEqual(gate.pausedUntil(), clock.now().addingTimeInterval(120))
    }

    func testRetryAfterIsHonouredUpToAnHourAndASuccessEndsThePause() async {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let pauses = LiveSourcePauses()
        let gate = makeGate(clock, pauses: pauses)
        _ = await gate.fetch("a") { .failure(.http(status: 503, retryAfter: 7_200)) }
        XCTAssertEqual(gate.pausedUntil(), clock.now().addingTimeInterval(3_600))
        clock.advance(3_601)
        let back = await gate.fetch("b") { .body(LiveBody(text: "ok", fetchedAt: clock.now())) }
        XCTAssertEqual(back, answered("ok", clock))
        XCTAssertNil(gate.pausedUntil())
        XCTAssertEqual(pauses.active(at: clock.now()), [])
    }

    func testThreeFailuresInARowPauseTheSourceForAMinute() async {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let gate = makeGate(clock)
        for key in ["a", "b"] {
            _ = await gate.fetch(key) { .failure(.timedOut) }
            XCTAssertNil(gate.pausedUntil(), key)
        }
        _ = await gate.fetch("c") { .failure(.http(status: 500, retryAfter: nil)) }
        XCTAssertEqual(gate.pausedUntil(), clock.now().addingTimeInterval(60))
    }

    func testACancelledCallerReturnsAtOnceAndTheRequestStopsWhenNobodyWaits() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let gate = makeGate(clock)
        let flags = LiveCounter()
        let load: @Sendable () async -> LiveFetchResult = {
            do {
                try await Task.sleep(nanoseconds: 30_000_000_000)
            } catch {
                flags.mark("load cancelled")
                return .failure(.cancelled)
            }
            return .failure(.network)
        }
        let started = Date()
        let task = Task { await gate.fetch("slow", load: load) }
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        let result = await task.value
        XCTAssertEqual(result, .failure(.cancelled))
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)
        for _ in 0..<50 where !flags.marked("load cancelled") { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertTrue(flags.marked("load cancelled"), "the request is cancelled when its only caller leaves")
        // A cancellation is not remembered: the next caller asks again.
        let loads = LiveCounter()
        let again = await gate.fetch("slow") {
            loads.increment()
            return .body(LiveBody(text: "ok", fetchedAt: clock.now()))
        }
        XCTAssertEqual(again, answered("ok", clock))
        XCTAssertEqual(loads.value, 1)
    }

    func testOneCallerLeavingDoesNotCancelTheRequestOfAnother() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let gate = makeGate(clock)
        let answer = answered("board", clock)
        let loads = LiveCounter()
        let load: @Sendable () async -> LiveFetchResult = {
            loads.increment()
            try? await Task.sleep(nanoseconds: 400_000_000)
            return answer
        }
        let leaving = Task { await gate.fetch("shared", load: load) }
        let staying = Task { await gate.fetch("shared", load: load) }
        try await Task.sleep(nanoseconds: 100_000_000)
        leaving.cancel()
        let left = await leaving.value
        let stayed = await staying.value
        XCTAssertEqual(left, .failure(.cancelled))
        XCTAssertEqual(stayed, answer)
        XCTAssertEqual(loads.value, 1)
    }

    func testDeadlinesAndBoundedParallelWorkKeepTheOrder() async {
        let fast = await LiveTasks.withDeadline(seconds: 5) { 7 }
        XCTAssertEqual(fast, 7)
        let slow = await LiveTasks.withDeadline(seconds: 0.1) { () async -> Int in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            return 8
        }
        XCTAssertNil(slow)
        let running = LiveRunningCount()
        let squares = await LiveTasks.boundedMap(Array(1...9), maximumConcurrent: 3) { value -> Int in
            running.enter()
            try? await Task.sleep(nanoseconds: 20_000_000)
            running.leave()
            return value * value
        }
        XCTAssertEqual(squares, [1, 4, 9, 16, 25, 36, 49, 64, 81])
        XCTAssertLessThanOrEqual(running.maximum, 3)
    }
}

/// The most tasks seen running at once.
final class LiveRunningCount: @unchecked Sendable {
    private let lock = NSLock()
    private var current = 0
    private var peak = 0

    func enter() {
        lock.lock()
        current += 1
        peak = max(peak, current)
        lock.unlock()
    }

    func leave() {
        lock.lock()
        current -= 1
        lock.unlock()
    }

    var maximum: Int {
        lock.lock()
        defer { lock.unlock() }
        return peak
    }
}
