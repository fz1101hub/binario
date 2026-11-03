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
            self?.activationHandler?(state)
        }
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
