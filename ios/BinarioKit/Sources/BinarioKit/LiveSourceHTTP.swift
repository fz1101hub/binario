import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The User-Agent of every live request: Binario, honestly, in the style of Android's
/// `BINARIO_PUBLIC_SOURCE_USER_AGENT` («Binario/0.20 (personal rail information client)»), with the
/// platform named. Never a browser's.
public enum LiveSourceUserAgent {
    public static let general = "Binario/0.1 (iOS; personal rail information client)"
    /// ViaggiaTreno's realtime API, as Android's `HttpViaggiaTrenoRealtimeFetcher` names itself.
    public static let viaggiaTrenoRealtime = "Binario/0.1 (iOS; personal realtime client)"
}

/// Why a request gave no body. A value, never an error thrown to the caller.
public enum LiveFetchFailure: Equatable, Hashable, Sendable {
    /// Not an HTTPS URL without credentials.
    case invalidRequest
    case network
    case timedOut
    /// The body (declared or received) is larger than the policy allows: refused, never truncated.
    case tooLarge
    /// A redirect the policy does not allow, or one the system followed by itself.
    case redirectRefused
    case tooManyRedirects
    case http(status: Int, retryAfter: TimeInterval?)
    /// The source is paused after a refusal or repeated failures: nothing was sent.
    case paused(until: Date)
    case cancelled

    var retryAfter: TimeInterval? {
        if case let .http(_, retryAfter) = self { return retryAfter }
        return nil
    }

    /// HTTP 403 or 429: the source said no. Binario stops asking at once.
    var isRefusal: Bool {
        if case let .http(status, _) = self { return status == 403 || status == 429 }
        return false
    }

    var unavailableReason: LiveUnavailableReason {
        switch self {
        case .invalidRequest: return .invalidRequest
        case let .paused(until): return .paused(until: until)
        case .cancelled: return .cancelled
        case let .http(status, _):
            if status == 403 || status == 429 { return .refused }
            return status == 404 ? .notFound : .sourceUnavailable
        case .network, .timedOut, .tooLarge, .redirectRefused, .tooManyRedirects: return .sourceUnavailable
        }
    }
}

/// A body received from a source and when.
struct LiveBody: Equatable, Sendable {
    let text: String
    let fetchedAt: Date
}

enum LiveFetchResult: Equatable, Sendable {
    case body(LiveBody)
    case failure(LiveFetchFailure)
}

/// The limits of one GET, Android's `PublicSourceRequestPolicy` and the adapters' own fetchers.
struct LiveRequestPolicy: Equatable, Sendable {
    var accept: String
    var userAgent: String = LiveSourceUserAgent.general
    var extraHeaders: [String: String] = [:]
    /// The longest wait for data on one request (Android's read timeout).
    var requestTimeout: TimeInterval
    /// The whole call, redirects included (Android's `withTimeoutOrNull`).
    var totalTimeout: TimeInterval
    var maximumBytes: Int
    /// Requests per call, redirects included (Android: 3, ViaggiaTreno realtime 4).
    var maximumRequests: Int = 3
    /// The single cleartext hop Android permits: an HTTPS request to www.viaggiatreno.it redirected
    /// to the very same path and query over HTTP on www.viaggiatreno.it.
    var allowsViaggiaTrenoCleartextHop = false
    /// The ViaggiaTreno realtime client's HTTPS variants: the same resource on viaggiatreno.it, and
    /// a cleartext redirect elsewhere on www.viaggiatreno.it asked again over HTTPS on viaggiatreno.it.
    var allowsViaggiaTrenoAlternateHost = false

    static let redirectStatuses: Set<Int> = [301, 302, 303, 307, 308]
}

/// The redirect rule of Android's public-source clients: the same resource, over HTTPS, on the
/// same host; the one ViaggiaTreno cleartext hop when the policy allows it. Everything else is refused.
enum LiveRedirect {
    static let viaggiaTrenoHost = "www.viaggiatreno.it"
    static let viaggiaTrenoApexHost = "viaggiatreno.it"
    static let maximumLocationLength = 2_048

    /// The URL to ask next when `current` (first asked as `requested`) answers a redirect to
    /// `location`, or nil when the redirect is refused.
    static func next(requested: URL, current: URL, location: String, policy: LiveRequestPolicy) -> URL? {
        let trimmed = location.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= maximumLocationLength,
              let resolved = URL(string: trimmed, relativeTo: current)?.absoluteURL,
              let target = URLComponents(url: resolved, resolvingAgainstBaseURL: false),
              let origin = URLComponents(url: requested, resolvingAgainstBaseURL: false),
              let from = URLComponents(url: current, resolvingAgainstBaseURL: false) else { return nil }
        let scheme = target.scheme?.lowercased() ?? ""
        let host = target.host?.lowercased() ?? ""
        let requestedHost = origin.host?.lowercased() ?? ""
        let currentHost = from.host?.lowercased() ?? ""
        let withoutCredentials = target.user == nil && target.password == nil
        let viaggiaTrenoHosts: Set<String> = [viaggiaTrenoHost, viaggiaTrenoApexHost]

        // `HttpViaggiaTrenoRealtimeFetcher`: a cleartext redirect from www.viaggiatreno.it to another
        // resource on it (its home page) is not followed; the same resource is asked over HTTPS on
        // viaggiatreno.it instead.
        if policy.allowsViaggiaTrenoAlternateHost, scheme == "http", host == viaggiaTrenoHost, defaultPort(target, 80),
           withoutCredentials, currentHost == viaggiaTrenoHost,
           !(target.fragment == nil && sameResource(target, origin)) {
            var alternate = URLComponents()
            alternate.scheme = "https"
            alternate.host = viaggiaTrenoApexHost
            alternate.percentEncodedPath = from.percentEncodedPath
            alternate.percentEncodedQuery = from.percentEncodedQuery
            return alternate.url
        }
        guard withoutCredentials, target.fragment == nil, sameResource(target, origin) else { return nil }
        if scheme == "https", host == requestedHost, defaultPort(target, 443) { return resolved }
        if policy.allowsViaggiaTrenoAlternateHost, scheme == "https", viaggiaTrenoHosts.contains(host),
           viaggiaTrenoHosts.contains(requestedHost), defaultPort(target, 443) { return resolved }
        if policy.allowsViaggiaTrenoCleartextHop, scheme == "http", host == viaggiaTrenoHost,
           requestedHost == viaggiaTrenoHost, defaultPort(target, 80) { return resolved }
        return nil
    }

    private static func sameResource(_ first: URLComponents, _ second: URLComponents) -> Bool {
        first.percentEncodedPath == second.percentEncodedPath && first.percentEncodedQuery == second.percentEncodedQuery
    }

    private static func defaultPort(_ components: URLComponents, _ port: Int) -> Bool {
        components.port == nil || components.port == port
    }

    /// The pause a `Retry-After` header asks for: delta-seconds or an HTTP date in the future.
    static func retryAfter(_ header: String?, now: Date) -> TimeInterval? {
        guard let value = header?.trimmingCharacters(in: .whitespaces), !value.isEmpty, value.count <= 64 else { return nil }
        if let seconds = Int64(value) {
            return seconds > 0 ? TimeInterval(min(seconds, 7 * 86_400)) : nil
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, d MMM yyyy HH:mm:ss zzz"
        guard let date = formatter.date(from: value) else { return nil }
        let interval = date.timeIntervalSince(now)
        return interval > 0 ? interval : nil
    }
}

/// One GET as the transport sends it.
public struct LiveHTTPRequest: Equatable, Sendable {
    public let url: URL
    public let headers: [String: String]
    /// The longest wait for data.
    public let timeout: TimeInterval
    /// The longest the whole request may take.
    public let resourceTimeout: TimeInterval
    public let maximumBytes: Int
}

public enum LiveTransportOutcome: Equatable, Sendable {
    /// The status, the headers with lower-case names, and the body (empty for a redirect).
    case response(status: Int, headers: [String: String], body: Data)
    case failure(LiveFetchFailure)
}

/// Sends one GET and never follows a redirect: each one comes back to `LiveSourceClient`, which
/// applies the redirect rule. Must honour task cancellation.
public protocol LiveHTTPTransport: Sendable {
    func send(_ request: LiveHTTPRequest) async -> LiveTransportOutcome
}

/// The iPhone transport: an ephemeral `URLSession` per request, without cookies, credential
/// storage or cache, that follows no redirect and stops reading past the byte limit.
public struct URLSessionLiveTransport: LiveHTTPTransport {
    public init() {}

    static func configuration(for request: LiveHTTPRequest) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = request.timeout
        configuration.timeoutIntervalForResource = request.resourceTimeout
        configuration.httpMaximumConnectionsPerHost = 2
        return configuration
    }

    static func urlRequest(for request: LiveHTTPRequest) -> URLRequest {
        var urlRequest = URLRequest(url: request.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: request.timeout)
        urlRequest.httpMethod = "GET"
        urlRequest.httpShouldHandleCookies = false
        for (name, value) in request.headers.sorted(by: { $0.key < $1.key }) {
            urlRequest.setValue(value, forHTTPHeaderField: name)
        }
        return urlRequest
    }

    public func send(_ request: LiveHTTPRequest) async -> LiveTransportOutcome {
        let delegate = LiveDataTaskDelegate(maximumBytes: request.maximumBytes, requestedURL: request.url)
        let session = URLSession(configuration: Self.configuration(for: request), delegate: delegate, delegateQueue: nil)
        let task = session.dataTask(with: Self.urlRequest(for: request))
        let outcome = await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<LiveTransportOutcome, Never>) in
                delegate.start(task, continuation: continuation)
            }
        } onCancel: {
            delegate.cancel()
        }
        session.finishTasksAndInvalidate()
        return outcome
    }
}

/// The state of one data task. URLSession calls it on its own serial queue; `start` and `cancel`
/// come from the calling task. Every access to the state holds the lock; the continuation is
/// resumed exactly once, outside the lock.
final class LiveDataTaskDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let maximumBytes: Int
    private let requestedURL: URL
    private var body = Data()
    private var continuation: CheckedContinuation<LiveTransportOutcome, Never>?
    private var task: URLSessionDataTask?
    private var cancelled = false
    private var finished = false
    private var overLimit = false

    init(maximumBytes: Int, requestedURL: URL) {
        self.maximumBytes = maximumBytes
        self.requestedURL = requestedURL
    }

    func start(_ task: URLSessionDataTask, continuation: CheckedContinuation<LiveTransportOutcome, Never>) {
        lock.lock()
        if cancelled {
            finished = true
            lock.unlock()
            continuation.resume(returning: .failure(.cancelled))
            return
        }
        self.continuation = continuation
        self.task = task
        lock.unlock()
        task.resume()
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let running = task
        lock.unlock()
        running?.cancel()
    }

    private func finish(_ outcome: LiveTransportOutcome) {
        lock.lock()
        guard !finished, let continuation else {
            lock.unlock()
            return
        }
        finished = true
        self.continuation = nil
        task = nil
        lock.unlock()
        continuation.resume(returning: outcome)
    }

    /// No redirect is followed by the system: the redirect response itself completes the task.
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        if overLimit {
            lock.unlock()
            return
        }
        let declared = dataTask.response?.expectedContentLength ?? -1
        let limit = Int64(maximumBytes)
        let total = Int64(body.count) + Int64(data.count)
        if declared > limit || total > limit {
            overLimit = true
            body = Data()
            lock.unlock()
            dataTask.cancel()
            return
        }
        body.append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        lock.lock()
        let tooLarge = overLimit
        let cancelledByCaller = cancelled
        let received = body
        lock.unlock()
        if tooLarge { return finish(.failure(.tooLarge)) }
        if cancelledByCaller { return finish(.failure(.cancelled)) }
        if let error {
            let code = (error as NSError).code
            let domain = (error as NSError).domain
            if domain == NSURLErrorDomain, code == NSURLErrorTimedOut { return finish(.failure(.timedOut)) }
            if domain == NSURLErrorDomain, code == NSURLErrorCancelled { return finish(.failure(.cancelled)) }
            return finish(.failure(.network))
        }
        guard let response = task.response as? HTTPURLResponse else { return finish(.failure(.network)) }
        // Should the system ever follow a redirect by itself, the answer is not the one asked for.
        if let answered = response.url, answered.absoluteString != requestedURL.absoluteString {
            return finish(.failure(.redirectRefused))
        }
        if response.expectedContentLength > Int64(maximumBytes) { return finish(.failure(.tooLarge)) }
        var headers: [String: String] = [:]
        for (name, value) in response.allHeaderFields {
            guard let name = name as? String, let value = value as? String, headers.count < 64 else { continue }
            headers[name.lowercased()] = value
        }
        finish(.response(status: response.statusCode, headers: headers, body: received))
    }
}

/// One call to a source: the HTTPS GET, the allowed redirects, the byte and time limits.
struct LiveSourceClient: Sendable {
    let transport: any LiveHTTPTransport
    let now: @Sendable () -> Date

    func get(_ url: URL, policy: LiveRequestPolicy) async -> LiveFetchResult {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https", components.user == nil, components.password == nil,
              components.host?.isEmpty == false else { return .failure(.invalidRequest) }
        let deadline = now().addingTimeInterval(policy.totalTimeout)
        var headers = policy.extraHeaders
        headers["Accept"] = policy.accept
        headers["User-Agent"] = policy.userAgent
        var current = url
        for hop in 0..<max(1, policy.maximumRequests) {
            if Task.isCancelled { return .failure(.cancelled) }
            let remaining = deadline.timeIntervalSince(now())
            guard remaining > 0 else { return .failure(.timedOut) }
            let request = LiveHTTPRequest(
                url: current, headers: headers, timeout: min(policy.requestTimeout, remaining),
                resourceTimeout: remaining, maximumBytes: policy.maximumBytes
            )
            switch await transport.send(request) {
            case let .failure(failure):
                return .failure(failure)
            case let .response(status, responseHeaders, body):
                if LiveRequestPolicy.redirectStatuses.contains(status) {
                    guard let location = responseHeaders["location"],
                          let target = LiveRedirect.next(requested: url, current: current, location: location, policy: policy) else {
                        return .failure(.redirectRefused)
                    }
                    if hop + 1 >= policy.maximumRequests { return .failure(.tooManyRedirects) }
                    current = target
                    continue
                }
                guard (200..<300).contains(status) else {
                    return .failure(.http(status: status, retryAfter: LiveRedirect.retryAfter(responseHeaders["retry-after"], now: now())))
                }
                guard body.count <= policy.maximumBytes else { return .failure(.tooLarge) }
                if Task.isCancelled { return .failure(.cancelled) }
                return .body(LiveBody(text: status == 204 ? "" : String(decoding: body, as: UTF8.self), fetchedAt: now()))
            }
        }
        return .failure(.tooManyRedirects)
    }
}
