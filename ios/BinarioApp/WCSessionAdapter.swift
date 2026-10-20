import Foundation
import BinarioKit

#if canImport(WatchConnectivity)
import WatchConnectivity

@MainActor
final class WCSessionAdapter: NSObject, WatchSnapshotSession, WCSessionDelegate {
    private let session: WCSession

    var activationHandler: (@MainActor (WatchSessionActivationState) -> Void)?

    var isSupported: Bool {
        WCSession.isSupported()
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
}
#endif
