import AppIntents

struct RefreshBinarioIntent: AppIntent {
    static var title: LocalizedStringResource = "Aggiorna Binario"
    static var description = IntentDescription("Rileggere lo snapshot ferroviario già disponibile sul dispositivo.")

    func perform() async throws -> some IntentResult {
        _ = WatchSnapshotStorage.load()
        return .result()
    }
}
