import Foundation
import BinarioKit

#if canImport(WatchConnectivity)
import WatchConnectivity

@MainActor
final class WCSessionAdapter: NSObject, WatchSnapshotSession, WCSessionDelegate {
    private let session: WCSession

    var activationHandler: (@MainActor (WatchSessionActivationState) -> Void)?
    var watchStateHandler: (@MainActor () -> Void)?
    var refreshRequestHandler: (@MainActor () -> Void)?
    var capabilitiesHandler: (@MainActor () -> Void)?
    /// What the Watch app declared in its own application context (`WatchCapabilities`).
    private(set) var watchCapabilities: Set<String> = []

    var isSupported: Bool {
        WCSession.isSupported()
    }

    var isPaired: Bool {
        isSupported && session.isPaired
    }

    var isWatchAppInstalled: Bool {
        isSupported && session.isWatchAppInstalled
    }

    var activationState: WatchSessionActivationState {
        guard isSupported else { return .unsupported }
        switch session.activationState {
        case .notActivated: return .notActivated
        case .inactive: return .inactive
        case .activated: return .activated
        @unknown default: return .failed
        }
    }

    override init() {
        session = .default
        super.init()
        session.delegate = self
    }

    func activate() {
        guard isSupported else {
            activationHandler?(.unsupported)
            return
        }
        session.activate()
    }

    func updateApplicationContext(_ context: [String: Data]) throws {
        guard isSupported else { throw WearSnapshotError.unsupportedSession }
        guard session.activationState == .activated else { throw WearSnapshotError.publicationFailed }
        try session.updateApplicationContext(context)
    }

    nonisolated func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        let state: WatchSessionActivationState
        // The Watch app's declaration of the optional fields it decodes, as last received.
        let capabilities = error == nil ? WatchCapabilities.decode(session.receivedApplicationContext) : []
        if error != nil {
            state = .failed
        } else {
            switch activationState {
            case .notActivated: state = .notActivated
            case .inactive: state = .inactive
            case .activated: state = .activated
            @unknown default: state = .failed
            }
        }
        Task { @MainActor [weak self] in
            self?.receive(capabilities)
            self?.activationHandler?(state)
        }
    }

    /// The Watch app published its application context: the optional fields it decodes.
    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let capabilities = WatchCapabilities.decode(applicationContext)
        Task { @MainActor [weak self] in
            self?.receive(capabilities)
        }
    }

    private func receive(_ capabilities: Set<String>) {
        guard capabilities != watchCapabilities else { return }
        watchCapabilities = capabilities
        capabilitiesHandler?()
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {
        Task { @MainActor [weak self] in
            self?.activationHandler?(.inactive)
        }
    }

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        Task { @MainActor [weak self] in
            self?.activationHandler?(.inactive)
            self?.activate()
        }
    }

    /// Pairing or the installation of the Watch app changed: activation does not complete again,
    /// so this is what delivers a snapshot held back while no Watch app could receive it.
    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor [weak self] in
            self?.watchStateHandler?()
        }
    }

    /// "Aggiorna" on the Watch. The reply only acknowledges receipt and is sent at once, from this
    /// Watch Connectivity queue; the refreshed snapshot follows as a new application context.
    nonisolated func session(
        _ session: WCSession,
        didReceiveMessage message: [String: Any],
        replyHandler: @escaping ([String: Any]) -> Void
    ) {
        let isRefreshRequest = WatchRefreshRequest.isRefreshRequest(message)
        replyHandler([WatchRefreshRequest.messageKey: isRefreshRequest])
        guard isRefreshRequest else { return }
        Task { @MainActor [weak self] in
            self?.refreshRequestHandler?()
        }
    }
}
#endif
