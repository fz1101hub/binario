import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import BinarioKit

final class LiveSourceHTTPTests: XCTestCase {
    private let clock = LiveTestClock(Date(timeIntervalSince1970: 1_793_000_000))
    private let realtimePath = "https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/partenze/S08328/today"
    private let noticePath = "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829"

    private func client(_ transport: FakeLiveTransport) -> LiveSourceClient {
        LiveSourceClient(transport: transport, now: clock.closure)
    }

    private var publicPolicy: LiveRequestPolicy {
        LiveRequestPolicy(accept: "application/json", requestTimeout: 12, totalTimeout: 15, maximumBytes: 1_048_576)
    }

    func testEveryRequestIsHTTPSWithTheHonestIPhoneUserAgentAndNoBrowserIdentity() async throws {
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok("[]") }
        let url = try XCTUnwrap(URL(string: "https://iechub.rfi.it/ArriviPartenze"))
        let result = await client(transport).get(url, policy: RfiLiveSource.policy)
        guard case let .body(body) = result else { return XCTFail("expected a body, got \(result)") }
        XCTAssertEqual(body.text, "[]")
        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.headers["User-Agent"], "Binario/0.1 (iOS; personal rail information client)")
        XCTAssertEqual(request.headers["Accept"], "text/html,application/xhtml+xml")
        XCTAssertEqual(request.headers["Cache-Control"], "no-cache")
        for agent in [LiveSourceUserAgent.general, LiveSourceUserAgent.viaggiaTrenoRealtime] {
            XCTAssertTrue(agent.hasPrefix("Binario/"))
            XCTAssertTrue(agent.contains("iOS"))
            XCTAssertFalse(agent.contains("Mozilla"))
            XCTAssertFalse(agent.contains("Safari"))
        }
        XCTAssertEqual(ViaggiaTrenoLiveSource.policy(for: "cercaNumeroTrenoTrenoAutocomplete/23263").accept, "text/plain")
        XCTAssertEqual(ViaggiaTrenoLiveSource.policy(for: "partenze/S08328/x").accept, "application/json")

        let cleartext = try XCTUnwrap(URL(string: "http://www.trenord.it/x"))
        let refused = await client(transport).get(cleartext, policy: publicPolicy)
        XCTAssertEqual(refused, .failure(.invalidRequest))
        let credentials = try XCTUnwrap(URL(string: "https://user:secret@www.viaggiatreno.it/x"))
        let refusedCredentials = await client(transport).get(credentials, policy: publicPolicy)
        XCTAssertEqual(refusedCredentials, .failure(.invalidRequest))
        XCTAssertEqual(transport.requests.count, 1, "nothing is sent for a refused URL")
    }

    func testTheOnlyCleartextHopIsTheSameViaggiaTrenoResourceAndOnlyWhenAllowed() async throws {
        let requested = try XCTUnwrap(URL(string: noticePath))
        let hop = noticePath.replacingOccurrences(of: "https://", with: "http://")
        let allowed = LiveRequestPolicy(
            accept: "application/json", requestTimeout: 12, totalTimeout: 15, maximumBytes: 1_048_576,
            allowsViaggiaTrenoCleartextHop: true
        )
        let transport = FakeLiveTransport { request in
            request.url.scheme == "https" ? FakeLiveTransport.redirect(hop) : FakeLiveTransport.ok("[]")
        }
        let followed = await client(transport).get(requested, policy: allowed)
        XCTAssertEqual(followed, .body(LiveBody(text: "[]", fetchedAt: clock.now())))
        XCTAssertEqual(transport.requests.map { $0.url.scheme ?? "" }, ["https", "http"])
        XCTAssertTrue(transport.requests.allSatisfy { $0.url.host == "www.viaggiatreno.it" && $0.url.query == "commercialTrainNumber=8829" })

        // Without the explicit permission even the exact ViaggiaTreno hop is refused.
        let strict = FakeLiveTransport { _ in FakeLiveTransport.redirect(hop) }
        let refused = await client(strict).get(requested, policy: publicPolicy)
        XCTAssertEqual(refused, .failure(.redirectRefused))
        XCTAssertEqual(strict.requests.count, 1)
    }

    func testRedirectsOutsideTheAllowedHopAreRefusedWithoutBeingAsked() async throws {
        let requested = try XCTUnwrap(URL(string: noticePath))
        let allowed = LiveRequestPolicy(
            accept: "application/json", requestTimeout: 12, totalTimeout: 15, maximumBytes: 1_048_576,
            allowsViaggiaTrenoCleartextHop: true
        )
        for location in [
            "http://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=9999",
            "http://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility?commercialTrainNumber=8829",
            "http://viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829",
            "http://www.viaggiatreno.it:8080/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829",
            "http://user@www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829",
            "https://example.org/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829",
            "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829#frammento",
            "",
        ] {
            let transport = FakeLiveTransport { _ in FakeLiveTransport.redirect(location) }
            let result = await client(transport).get(requested, policy: allowed)
            XCTAssertEqual(result, .failure(.redirectRefused), location)
            XCTAssertEqual(transport.requests.count, 1, location)
        }
        let missing = FakeLiveTransport { _ in FakeLiveTransport.status(302) }
        let withoutLocation = await client(missing).get(requested, policy: allowed)
        XCTAssertEqual(withoutLocation, .failure(.redirectRefused))
    }

    func testViaggiaTrenoRealtimeKeepsAndroidsHTTPSAlternativesAndRefusesTheRest() throws {
        let policy = ViaggiaTrenoLiveSource.policy(for: "partenze/S08328/today")
        let requested = try XCTUnwrap(URL(string: realtimePath))
        let path = "/infomobilita/resteasy/viaggiatreno/partenze/S08328/today"
        // The same resource on viaggiatreno.it over HTTPS, and the legacy cleartext API on www.
        XCTAssertEqual(
            LiveRedirect.next(requested: requested, current: requested, location: "https://viaggiatreno.it" + path, policy: policy)?.absoluteString,
            "https://viaggiatreno.it" + path
        )
        XCTAssertEqual(
            LiveRedirect.next(requested: requested, current: requested, location: "http://www.viaggiatreno.it" + path, policy: policy)?.absoluteString,
            "http://www.viaggiatreno.it" + path
        )
        // A cleartext redirect to the home page: the same resource is asked over HTTPS on viaggiatreno.it.
        XCTAssertEqual(
            LiveRedirect.next(requested: requested, current: requested, location: "http://www.viaggiatreno.it/", policy: policy)?.absoluteString,
            "https://viaggiatreno.it" + path
        )
        let apex = try XCTUnwrap(URL(string: "https://viaggiatreno.it" + path))
        for location in [
            "http://viaggiatreno.it" + path,
            "http://www.viaggiatreno.it:81" + path,
            "http://user@www.viaggiatreno.it" + path,
            "https://example.org" + path,
            "https://www.viaggiatreno.it:444" + path,
            "https://user@www.viaggiatreno.it" + path,
            "https://www.viaggiatreno.it/login",
        ] {
            XCTAssertNil(LiveRedirect.next(requested: requested, current: requested, location: location, policy: policy), location)
        }
        // From viaggiatreno.it a cleartext redirect elsewhere is not rewritten again.
        XCTAssertNil(LiveRedirect.next(requested: requested, current: apex, location: "http://www.viaggiatreno.it" + path + "?token=x", policy: policy))
        // Other sources never get the ViaggiaTreno alternatives.
        XCTAssertNil(LiveRedirect.next(requested: requested, current: requested, location: "https://viaggiatreno.it" + path, policy: publicPolicy))
        XCTAssertEqual(
            LiveRedirect.next(requested: requested, current: requested, location: path, policy: publicPolicy)?.absoluteString,
            realtimePath, "a relative redirect to the same resource on the same HTTPS host"
        )
    }

    func testARedirectLoopIsBoundedAndNeverReadAsAnEmptyBoard() async throws {
        let requested = try XCTUnwrap(URL(string: realtimePath))
        let transport = FakeLiveTransport { request in FakeLiveTransport.redirect(request.url.absoluteString) }
        let result = await client(transport).get(requested, policy: ViaggiaTrenoLiveSource.policy(for: "partenze/x"))
        XCTAssertEqual(result, .failure(.tooManyRedirects))
        XCTAssertEqual(transport.requests.count, 4, "Android's ViaggiaTreno client asks four times at most")
        let public3 = FakeLiveTransport { request in FakeLiveTransport.redirect(request.url.absoluteString) }
        _ = await client(public3).get(requested, policy: publicPolicy)
        XCTAssertEqual(public3.requests.count, 3, "Android's public-source client asks three times at most")
    }

    func testStatusesAreTypedAndRetryAfterIsRead() async throws {
        let url = try XCTUnwrap(URL(string: "https://www.trenord.it/x"))
        let unavailable = FakeLiveTransport { _ in FakeLiveTransport.status(503, headers: ["retry-after": "120"]) }
        let result = await client(unavailable).get(url, policy: publicPolicy)
        XCTAssertEqual(result, .failure(.http(status: 503, retryAfter: 120)))
        XCTAssertEqual(LiveFetchFailure.http(status: 429, retryAfter: nil).unavailableReason, .refused)
        XCTAssertEqual(LiveFetchFailure.http(status: 403, retryAfter: nil).unavailableReason, .refused)
        XCTAssertEqual(LiveFetchFailure.http(status: 404, retryAfter: nil).unavailableReason, .notFound)
        XCTAssertEqual(LiveFetchFailure.http(status: 500, retryAfter: nil).unavailableReason, .sourceUnavailable)
        XCTAssertTrue(LiveFetchFailure.http(status: 429, retryAfter: nil).isRefusal)
        XCTAssertFalse(LiveFetchFailure.http(status: 503, retryAfter: nil).isRefusal)

        let now = Date(timeIntervalSince1970: 1_793_000_000)
        XCTAssertEqual(LiveRedirect.retryAfter("30", now: now), 30)
        XCTAssertNil(LiveRedirect.retryAfter("0", now: now))
        XCTAssertNil(LiveRedirect.retryAfter("-5", now: now))
        XCTAssertNil(LiveRedirect.retryAfter("dopo", now: now))
        XCTAssertNil(LiveRedirect.retryAfter(nil, now: now))
        // 2026-10-26 07:33:20 UTC is `now`: an HTTP date an hour later asks an hour.
        let date = try XCTUnwrap(LiveRedirect.retryAfter("Mon, 26 Oct 2026 08:33:20 GMT", now: now))
        XCTAssertEqual(date, 3_600, accuracy: 1)
        XCTAssertNil(LiveRedirect.retryAfter("Mon, 26 Oct 2020 08:33:20 GMT", now: now), "a date in the past asks no pause")
    }

    func testAMissingConnectionIsTheDevicesNotTheSources() {
        func failure(_ code: Int) -> LiveFetchFailure {
            LiveDataTaskDelegate.failure(for: NSError(domain: NSURLErrorDomain, code: code))
        }
        for code in [
            NSURLErrorNotConnectedToInternet, NSURLErrorDataNotAllowed,
            NSURLErrorInternationalRoamingOff, NSURLErrorCallIsActive, NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed,
        ] {
            XCTAssertEqual(failure(code), .offline, "\(code)")
        }
        // A connection the server accepted and then dropped is the source's failure, never the device's.
        XCTAssertEqual(failure(NSURLErrorNetworkConnectionLost), .network)
        XCTAssertEqual(failure(NSURLErrorTimedOut), .timedOut, "a source that stops answering still counts")
        XCTAssertEqual(failure(NSURLErrorCancelled), .cancelled)
        XCTAssertEqual(failure(NSURLErrorSecureConnectionFailed), .network)
        XCTAssertEqual(LiveDataTaskDelegate.failure(for: NSError(domain: "other", code: NSURLErrorTimedOut)), .network)
    }

    func testBodiesOverTheLimitAreRefusedNeverTruncated() async throws {
        let url = try XCTUnwrap(URL(string: "https://www.trenord.it/x"))
        let small = LiveRequestPolicy(accept: "application/json", requestTimeout: 12, totalTimeout: 15, maximumBytes: 8)
        let large = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: Data(repeating: 0x41, count: 20)) }
        let result = await client(large).get(url, policy: small)
        XCTAssertEqual(result, .failure(.tooLarge))
        XCTAssertEqual(large.requests.first?.maximumBytes, 8, "the transport is told the limit")
    }

    func testTheCallerCanCancelARequestInFlight() async throws {
        let url = try XCTUnwrap(URL(string: "https://www.trenord.it/x"))
        let transport = FakeLiveTransport { _ in await FakeLiveTransport.stall() }
        let source = client(transport)
        let policy = publicPolicy
        let started = Date()
        let task = Task { await source.get(url, policy: policy) }
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        let result = await task.value
        XCTAssertEqual(result, .failure(.cancelled))
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)
    }

    func testTheIPhoneSessionIsEphemeralWithoutCookiesCredentialsOrCache() throws {
        let url = try XCTUnwrap(URL(string: "https://iechub.rfi.it/ArriviPartenze"))
        let request = LiveHTTPRequest(url: url, headers: ["Accept": "text/html"], timeout: 10, resourceTimeout: 10, maximumBytes: 1_024)
        let configuration = URLSessionLiveTransport.configuration(for: request)
        XCTAssertNil(configuration.httpCookieStorage)
        XCTAssertNil(configuration.urlCredentialStorage)
        XCTAssertNil(configuration.urlCache)
        XCTAssertFalse(configuration.httpShouldSetCookies)
        XCTAssertEqual(configuration.httpCookieAcceptPolicy, .never)
        XCTAssertEqual(configuration.requestCachePolicy, .reloadIgnoringLocalCacheData)
        XCTAssertEqual(configuration.timeoutIntervalForRequest, 10)
        XCTAssertEqual(configuration.timeoutIntervalForResource, 10)
        let urlRequest = URLSessionLiveTransport.urlRequest(for: request)
        XCTAssertEqual(urlRequest.httpMethod, "GET")
        XCTAssertFalse(urlRequest.httpShouldHandleCookies)
        XCTAssertEqual(urlRequest.value(forHTTPHeaderField: "Accept"), "text/html")
    }

    func testATaskCancelledBeforeItStartsIsNeverSent() async throws {
        let url = try XCTUnwrap(URL(string: "https://iechub.rfi.it/ArriviPartenze"))
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: url)
        let delegate = LiveDataTaskDelegate(maximumBytes: 1_024, requestedURL: url)
        delegate.cancel()
        let outcome = await withCheckedContinuation { (continuation: CheckedContinuation<LiveTransportOutcome, Never>) in
            delegate.start(task, continuation: continuation)
        }
        XCTAssertEqual(outcome, .failure(.cancelled))
        XCTAssertEqual(task.state, .suspended, "the task was never resumed")
    }

    func testACancelledRequestReleasesItsSessionWithTheTaskItNeverStarted() async throws {
        let url = try XCTUnwrap(URL(string: "https://iechub.rfi.it/ArriviPartenze"))
        let session = URLSession(configuration: .ephemeral)
        let task = session.dataTask(with: url)
        let delegate = LiveDataTaskDelegate(maximumBytes: 1_024, requestedURL: url)
        delegate.cancel()
        let outcome = await withCheckedContinuation { (continuation: CheckedContinuation<LiveTransportOutcome, Never>) in
            delegate.start(task, continuation: continuation)
        }
        XCTAssertEqual(outcome, .failure(.cancelled))
        URLSessionLiveTransport.invalidate(session, after: outcome)
        for _ in 0..<50 where task.state == .suspended { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertNotEqual(task.state, .suspended, "the task left suspended is cancelled, so the session can go")
        XCTAssertNotEqual(task.state, .running, "and it was never sent")
    }
}
