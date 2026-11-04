import Foundation
import WatchConnectivity

enum WatchApplicationContextAction: Equatable {
    static let snapshotKey = "binario_snapshot_json"
    static let clearKey = "binario_snapshot_clear"

    case accept(BinarioWatchSnapshot)
    case clear
    case ignore

    static func decode(
        _ context: [String: Any],
        nowEpochMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) -> WatchApplicationContextAction {
        // Revocation wins even if a malformed sender includes both transport keys.
        if context[clearKey] != nil {
            return .clear
        }
        guard let encoded = context[snapshotKey] as? Data,
              let snapshot = BinarioWatchSnapshot.decodeContract(
                encoded,
                nowEpochMillis: nowEpochMillis
              ) else {
            return .ignore
        }
        return .accept(snapshot)
    }
}

/// Riceve gli snapshot v1 prodotti dall'app iOS Binario associata. Il decoder
/// resta fail-closed; l'effettiva consegna iPhone↔Watch richiede una prova hardware.
final class PhoneConnectivity: NSObject, ObservableObject, WCSessionDelegate {
    private let store: WatchSnapshotStore

    init(store: WatchSnapshotStore) {
        self.store = store
        super.init()
    }

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }

    func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        guard error == nil else { return }
        apply(session.receivedApplicationContext)
    }

    func session(
        _ session: WCSession,
        didReceiveApplicationContext applicationContext: [String: Any]
    ) {
        apply(applicationContext)
    }

    private func apply(_ context: [String: Any]) {
        let action = WatchApplicationContextAction.decode(context)
        Task { @MainActor [store] in
            switch action {
            case let .accept(snapshot):
                store.accept(snapshot: snapshot)
            case .clear:
                store.clear()
            case .ignore:
                break
            }
        }
    }
}

enum PhoneRefreshMessage {
    /// Same key as WatchRefreshRequest.messageKey on the iPhone (BinarioKit).
    static let requestKey = "binario_refresh_request"
}

/// "Aggiorna" on the Watch (or the app opening with nothing to show): a Watch Connectivity message
/// that wakes the iPhone app, which repeats the route of its last search for now, or searches the
/// route pinned with «Mostra su Watch», and publishes the result as a new application context.
/// The reply only acknowledges receipt; the message carries no data besides the request flag.
@MainActor
final class WCSessionRefreshRequester: PhoneRefreshRequesting {
    func requestRefresh() async -> PhoneRefreshOutcome {
        guard WCSession.isSupported() else { return .unreachable }
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return .unreachable }
        return await withCheckedContinuation { continuation in
            Self.send(resuming: continuation)
        }
    }

    /// Watch Connectivity calls the handlers on its own queue: they are formed outside the main
    /// actor, so nothing expects them there, and they only resume the continuation. Exactly one
    /// of them runs, the error handler also on timeout.
    nonisolated private static func send(resuming continuation: CheckedContinuation<PhoneRefreshOutcome, Never>) {
        WCSession.default.sendMessage(
            [PhoneRefreshMessage.requestKey: true],
            replyHandler: { _ in continuation.resume(returning: .sent) },
            errorHandler: { _ in continuation.resume(returning: .unreachable) }
        )
    }
}
