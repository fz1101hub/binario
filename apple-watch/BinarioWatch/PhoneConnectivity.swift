import Foundation
import WatchConnectivity

/// Canale opzionale per una futura app iOS Binario. Apple Watch non può ricevere
/// dati dal telefono Android: su Android la continuità passa dal profilo remoto autenticato.
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

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        guard error == nil,
              let encoded = session.receivedApplicationContext["binario_snapshot_json"] as? Data,
              let snapshot = BinarioWatchSnapshot.decodeContract(encoded) else { return }
        Task { @MainActor in self.store.accept(snapshot: snapshot) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        guard let encoded = applicationContext["binario_snapshot_json"] as? Data,
              let snapshot = BinarioWatchSnapshot.decodeContract(encoded) else { return }
        Task { @MainActor in self.store.accept(snapshot: snapshot) }
    }
}
