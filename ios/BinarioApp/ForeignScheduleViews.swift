import SwiftUI
import BinarioKit

/// The texts of the full foreign timetables: one sentence per country, as on Android (no grammar
/// built from parts), with the size the pin gives for this iPhone. A country without its texts is
/// never offered.
enum ForeignTexts {
    static func supports(_ pack: ForeignSchedulePins.Pack) -> Bool {
        country(pack) != nil
    }

    /// «Svizzera», «Austria (ÖBB)», «Francia (SNCF)».
    static func country(_ pack: ForeignSchedulePins.Pack) -> String? {
        switch pack.country {
        case "CH": return NSLocalizedString("settings.foreign.country_ch", comment: "")
        case "AT": return NSLocalizedString("settings.foreign.country_at", comment: "")
        case "FR": return NSLocalizedString("settings.foreign.country_fr", comment: "")
        default: return nil
        }
    }

    /// The dialog: «Per questa tratta servono gli orari della Svizzera (2,7 MB). Scaricarli?»
    static func prompt(_ pack: ForeignSchedulePins.Pack) -> String? {
        let key: String
        switch pack.country {
        case "CH": key = "schedule.foreign.prompt_ch"
        case "AT": key = "schedule.foreign.prompt_at"
        case "FR": key = "schedule.foreign.prompt_fr"
        default: return nil
        }
        return format(key, downloadSize(pack))
    }

    /// One line: «Altri treni in Svizzera: scarica gli orari completi (2,7 MB)».
    static func banner(_ pack: ForeignSchedulePins.Pack) -> String? {
        let key: String
        switch pack.country {
        case "CH": key = "schedule.foreign.banner_ch"
        case "AT": key = "schedule.foreign.banner_at"
        case "FR": key = "schedule.foreign.banner_fr"
        default: return nil
        }
        return format(key, downloadSize(pack))
    }

    /// «Gli orari della Svizzera di questa versione sono terminati il …: aggiorna Binario.»
    static func ended(_ pack: ForeignSchedulePins.Pack) -> String? {
        let key: String
        switch pack.country {
        case "CH": key = "schedule.foreign.expired_ch"
        case "AT": key = "schedule.foreign.expired_at"
        case "FR": key = "schedule.foreign.expired_fr"
        default: return nil
        }
        return format(key, pack.lastServiceDate.formatted(date: .long, time: .omitted))
    }

    /// The line under a country in Settings.
    static func status(_ row: ForeignPackRow) -> String {
        switch row.status {
        case .borderOnly:
            return format("settings.foreign.status_border", downloadSize(row.pack))
        case .installed:
            return format("settings.foreign.status_installed", installedSize(row.pack))
        case .updateAvailable:
            return format("settings.foreign.status_update", downloadSize(row.pack))
        }
    }

    /// Why a download gave nothing; nil for a cancellation.
    static func failure(_ failure: ForeignScheduleDownloadFailure) -> String? {
        switch failure {
        case .network, .storage:
            return NSLocalizedString("schedule.foreign.error_network", comment: "")
        case .refused:
            return NSLocalizedString("schedule.foreign.error_refused", comment: "")
        case .invalid:
            return NSLocalizedString("schedule.foreign.error_invalid", comment: "")
        case .notOffered, .busy, .cancelled:
            return nil
        }
    }

    /// The size of the download, as the pin gives it.
    static func downloadSize(_ pack: ForeignSchedulePins.Pack) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(pack.ios?.sizeBytes ?? 0), countStyle: .file)
    }

    /// The size of the installed timetable on this iPhone.
    static func installedSize(_ pack: ForeignSchedulePins.Pack) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(pack.ios?.databaseSizeBytes ?? 0), countStyle: .file)
    }

    private static func format(_ key: String, _ argument: String) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: .current, argument)
    }
}

/// The offer of a full foreign timetable above the planner's result or the «Partenze» board: per
/// country its sentence with the size and «Scarica», the progress with «Annulla», the failure of a
/// download; «aggiorna Binario» for a pin whose timetable has ended.
struct ForeignOfferSection: View {
    let offer: ForeignScheduleOffer

    var body: some View {
        Section {
            ForEach(offer.packs, id: \.sourceID) { pack in
                if let text = offer.kind == .dialog ? ForeignTexts.prompt(pack) : ForeignTexts.banner(pack) {
                    ForeignPackDownloadRow(pack: pack, text: text)
                }
            }
            ForEach(offer.ended, id: \.sourceID) { pack in
                if let text = ForeignTexts.ended(pack) {
                    Label(text, systemImage: "calendar.badge.exclamationmark")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// A sentence (optional) and the «Scarica» of one pack, or its progress, or its failure.
struct ForeignPackDownloadRow: View {
    @EnvironmentObject private var model: AppModel
    let pack: ForeignSchedulePins.Pack
    let text: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let text {
                Label {
                    Text(verbatim: text)
                } icon: {
                    Image(systemName: "arrow.down.circle")
                }
            }
            switch model.foreignDownloadState(for: pack.sourceID) {
            case .downloading:
                HStack {
                    ProgressView()
                    Text("schedule.foreign.downloading")
                    Spacer()
                    Button("schedule.foreign.cancel") { model.cancelForeignDownload() }
                        .buttonStyle(.borderless)
                }
            case let .failed(failure):
                if let message = ForeignTexts.failure(failure) {
                    Text(verbatim: message)
                        .font(.footnote)
                        .foregroundStyle(BinarioPalette.statusError)
                }
                downloadButton
            case .idle:
                downloadButton
            }
        }
    }

    private var downloadButton: some View {
        Button("schedule.foreign.download") { model.downloadForeignPack(pack.sourceID) }
            .buttonStyle(.borderless)
            .disabled(model.isDownloadingForeignPack)
    }
}

/// «Orari dei paesi confinanti» in Settings: each pinned country with what this iPhone holds,
/// «Scarica» or «Elimina», and what GitHub sees. Hidden when no pack is pinned.
struct ForeignPacksSettingsSection: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if !model.foreignPacks.isEmpty {
            Section {
                ForEach(model.foreignPacks, id: \.pack.sourceID) { row in
                    ForeignPackSettingsRow(row: row)
                }
            } header: {
                Text("settings.foreign.title")
            } footer: {
                Text("settings.privacy.foreign_packs")
            }
        }
    }
}

private struct ForeignPackSettingsRow: View {
    @EnvironmentObject private var model: AppModel
    let row: ForeignPackRow

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: ForeignTexts.country(row.pack) ?? row.pack.country)
            Text(verbatim: ForeignTexts.status(row))
                .font(.footnote)
                .foregroundStyle(.secondary)
            if row.status == .installed {
                Button("settings.foreign.delete", role: .destructive) {
                    Task { await model.deleteForeignPack(row.pack.sourceID) }
                }
                .buttonStyle(.borderless)
                .disabled(model.isDownloadingForeignPack)
            } else if row.pack.hasEnded(before: ForeignSchedulePins.romeDay(Date())) {
                if let text = ForeignTexts.ended(row.pack) {
                    Text(verbatim: text)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else {
                ForeignPackDownloadRow(pack: row.pack, text: nil)
            }
        }
    }
}
