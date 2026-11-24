import SwiftUI
import UniformTypeIdentifiers
import BinarioKit
#if canImport(UIKit)
import UIKit
#endif

// Android's settings that the iPhone lacked: the theme, the app language, «Biglietti e
// assistenza», the local backup, «Suggerisci una stazione» and the credits of the CO2 estimate.

/// «Tema» and «Lingua dell'app». The theme is Android's three choices, kept on this iPhone; iOS
/// keeps each app's language in its own Settings, which the row opens (the native place of
/// Android's per-app language list).
struct AppearanceLanguageSection: View {
    @EnvironmentObject private var personal: PersonalModel
    @Environment(\.openURL) private var openURL

    var body: some View {
        Section {
            Picker("settings.theme.title", selection: Binding(get: { personal.theme }, set: { personal.setTheme($0) })) {
                ForEach(PersonalModel.Theme.allCases) { theme in
                    Text(theme.titleKey).tag(theme)
                }
            }
            .pickerStyle(.segmented)
            Text("settings.theme.description")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("settings.theme.title")
        }
        Section {
            Text("settings.language.description")
                .font(.footnote)
                .foregroundStyle(.secondary)
            if let settings = Self.appSettingsURL {
                Button {
                    openURL(settings)
                } label: {
                    Label("settings.language.open", systemImage: "globe")
                }
            }
        } header: {
            Text("settings.language.title")
        }
        MobilityPreferencesSection()
    }

    /// The app's page in the iPhone's Settings, where iOS keeps the app's language.
    @MainActor
    static var appSettingsURL: URL? {
        #if canImport(UIKit)
        URL(string: UIApplication.openSettingsURLString)
        #else
        nil
        #endif
    }
}

/// «Mobilità e accessibilità» (Android's `MobilityPreferencesPanel`): changes, interchange margin, step-free routes
/// and compact boards, with Android's summary. Stored and backed up; as on Android, no search reads them yet.
private struct MobilityPreferencesSection: View {
    @EnvironmentObject private var personal: PersonalModel

    var body: some View {
        let value = personal.mobility
        Section {
            Text("settings.mobility.description")
                .font(.footnote)
                .foregroundStyle(.secondary)
            // -1 stands for «Qualsiasi» (no limit).
            Picker("settings.mobility.changes", selection: Binding(get: { value.maxChanges ?? -1 }, set: { chosen in
                update { $0.maxChanges = chosen >= 0 ? chosen : nil }
            })) {
                Text("settings.mobility.changes_any").tag(-1)
                ForEach(SharedData.MobilityPreferences.changeChoices, id: \.self) { changes in
                    if changes == 0 {
                        Text("settings.mobility.changes_direct").tag(changes)
                    } else {
                        Text(PersonalTexts.mobilityMaxChanges(changes)).tag(changes)
                    }
                }
            }
            Picker("settings.mobility.margin", selection: Binding(get: { value.transferMarginMinutes }, set: { minutes in
                update { $0.transferMarginMinutes = minutes }
            })) {
                ForEach(SharedData.MobilityPreferences.transferMarginChoices, id: \.self) { minutes in
                    Text(PersonalTexts.minutes(minutes)).tag(minutes)
                }
            }
            Toggle("settings.mobility.step_free", isOn: Binding(get: { value.stepFreePreferred }, set: { on in
                update { $0.stepFreePreferred = on }
            }))
            Toggle("settings.mobility.compact", isOn: Binding(get: { value.compactTimetablePreferred }, set: { on in
                update { $0.compactTimetablePreferred = on }
            }))
            Text(PersonalTexts.mobilitySummary(value))
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("settings.mobility.title")
        }
    }

    private func update(_ change: (inout MobilityPreferences) -> Void) {
        var value = personal.mobility
        change(&value)
        personal.setMobility(value)
    }
}

/// The row of «Biglietti e assistenza».
struct TicketsAssistanceRow: View {
    var body: some View {
        NavigationLink {
            TicketsAssistanceView()
        } label: {
            Label {
                VStack(alignment: .leading, spacing: 3) {
                    Text("settings.tickets.title")
                    Text("settings.tickets.detail")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "ticket")
            }
        }
    }
}

/// «Biglietti e assistenza»: for every operator Binario shows timetables of, its official pages,
/// each with the domain it opens on. Links only: the app sells nothing and sends nothing to them.
struct TicketsAssistanceView: View {
    var body: some View {
        List {
            Section {
                Text("settings.tickets.description")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(OfficialLinks.all) { operatorLinks in
                Section {
                    ForEach(operatorLinks.links, id: \.url) { link in
                        Link(destination: link.url) {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(Self.titleKey(link.kind))
                                        .foregroundStyle(.primary)
                                    Text(verbatim: link.url.host ?? link.url.absoluteString)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: Self.symbol(link.kind))
                            }
                        }
                    }
                } header: {
                    Text(verbatim: operatorLinks.name)
                }
            }
        }
        .navigationTitle("settings.tickets.title")
    }

    static func titleKey(_ kind: OperatorLinkKind) -> LocalizedStringKey {
        switch kind {
        case .buy: "settings.tickets.buy"
        case .change: "settings.tickets.change"
        case .subscriptions: "settings.tickets.subscriptions"
        case .bikes: "settings.tickets.bikes"
        case .customerCare: "settings.tickets.customer_care"
        case .refunds: "settings.tickets.refunds"
        case .strikes: "settings.tickets.strikes"
        case .website: "settings.tickets.website"
        }
    }

    static func symbol(_ kind: OperatorLinkKind) -> String {
        switch kind {
        case .buy: "ticket"
        case .change: "calendar.badge.clock"
        case .subscriptions: "creditcard"
        case .bikes: "bicycle"
        case .customerCare: "person.wave.2"
        case .refunds: "eurosign.circle"
        case .strikes: "calendar.badge.exclamationmark"
        case .website: "globe"
        }
    }
}

/// «Backup locale»: «Esporta» writes Android's backup file where the user chooses, «Importa» reads
/// one back. No cloud is involved.
struct LocalBackupSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel
    @EnvironmentObject private var trips: TripMonitor
    @EnvironmentObject private var journeys: JourneysModel
    @State private var importing = false
    @State private var feedback: String?

    var body: some View {
        Section {
            Text("settings.backup.description")
                .font(.footnote)
                .foregroundStyle(.secondary)
            switch exported {
            case let .success(data):
                ShareLink(item: BackupJSONFile(data: data), preview: SharePreview("settings.backup.title")) {
                    Label("action.export", systemImage: "square.and.arrow.up")
                }
            case let .failure(reason):
                Label("action.export", systemImage: "square.and.arrow.up")
                    .foregroundStyle(.secondary)
                Text(PersonalTexts.format("settings.backup.export_failure", Self.reason(reason)))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Button {
                importing = true
            } label: {
                Label("action.import", systemImage: "square.and.arrow.down")
            }
            if let feedback {
                Text(feedback)
                    .font(.footnote)
            }
        } header: {
            Text("settings.backup.title")
        }
        // Cancelling the picker is not an error: nothing is called then.
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText]) { result in
            guard case let .success(url) = result else { return }
            importFile(url)
        }
    }

    private var exported: Result<Data, LocalBackupFailure> {
        let backup = LocalBackup(
            routes: model.savedRoutes,
            favouriteStations: personal.favouriteStations,
            favouriteTrains: model.favouriteTrains,
            trainNames: personal.trainNames,
            punctualityHistory: personal.punctuality,
            savedTimetables: personal.savedTimetables,
            stationMonitors: personal.stationMonitors.map { BackupStationMonitor(station: $0.station, mode: $0.mode.rawValue) },
            pinnedRouteIDs: personal.pinnedRouteIDs,
            commuterAlerts: trips.commuterAlerts,
            travelPreferences: journeys.preferences,
            mobilityPreferences: personal.mobility
        )
        return LocalBackupCodec.encode(backup) { model.station(for: $0) }
    }

    private func importFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let limit = SharedData.LocalBackup.maximumBytes
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            report(PersonalTexts.format("settings.backup.import_failure", NSLocalizedString("settings.backup.file_error", comment: "")))
            return
        }
        defer { try? handle.close() }
        guard let data = try? handle.read(upToCount: limit + 1) else {
            report(PersonalTexts.format("settings.backup.import_failure", NSLocalizedString("settings.backup.file_error", comment: "")))
            return
        }
        switch LocalBackupCodec.decode(data, catalogue: { id in model.catalogStation(id: id) }, stationNamed: { name in model.catalogStation(named: name) }) {
        case let .failure(reason):
            report(PersonalTexts.format("settings.backup.import_failure", Self.reason(reason)))
        case let .success(backup):
            guard model.replaceFromBackup(routes: backup.routes, trains: backup.favouriteTrains) else {
                report(PersonalTexts.format("settings.backup.import_failure", NSLocalizedString("settings.backup.file_error", comment: "")))
                return
            }
            personal.replaceFavouriteStations(backup.favouriteStations)
            for train in backup.favouriteTrains {
                personal.renameTrain(id: train.id, name: backup.trainNames[train.id] ?? "")
            }
            if let history = backup.punctualityHistory { personal.replacePunctuality(history) }
            if let timetables = backup.savedTimetables { personal.replaceTimetables(timetables) }
            personal.replaceStationMonitors(backup.stationMonitors.compactMap { monitor in
                PersonalModel.MonitorMode(rawValue: monitor.mode).map { PersonalModel.StationMonitor(station: monitor.station, mode: $0) }
            })
            personal.replacePins(backup.pinnedRouteIDs)
            if let travel = backup.travelPreferences { journeys.setPreferences(travel) }
            // As on Android, a file without them gives the defaults.
            personal.setMobility(backup.mobilityPreferences)
            let alerts = backup.commuterAlerts
            Task { await trips.replaceCommuterAlerts(alerts) }
            if backup.routes.isEmpty {
                report(NSLocalizedString("settings.backup.imported_without_route", comment: ""))
            } else {
                report(PersonalTexts.format(
                    "settings.backup.import_summary",
                    String.localizedStringWithFormat(NSLocalizedString("settings.backup.routes", comment: ""), backup.routes.count),
                    String.localizedStringWithFormat(NSLocalizedString("settings.backup.stations", comment: ""), backup.favouriteStations.count),
                    String.localizedStringWithFormat(NSLocalizedString("settings.backup.alerts", comment: ""), backup.commuterAlerts.count)
                ))
            }
        }
    }

    private func report(_ text: String) {
        feedback = text
        DeparturesTexts.announce(text)
    }

    static func reason(_ failure: LocalBackupFailure) -> String {
        switch failure {
        case .tooLarge: NSLocalizedString("settings.backup.too_large", comment: "")
        case .nothing: NSLocalizedString("settings.backup.nothing", comment: "")
        case .unreadable: NSLocalizedString("settings.backup.file_error", comment: "")
        }
    }
}

/// The backup file, made when the user shares it: «binario-backup.json», as Android names it.
struct BackupJSONFile: Transferable {
    let data: Data

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .json) { file in file.data }
            .suggestedFileName("binario-backup.json")
    }
}

/// «Suggerisci una stazione»: the project's public issue form, with Android's prefilled title.
struct SuggestStationRow: View {
    var body: some View {
        if let url = OfficialLinks.newIssue(title: NSLocalizedString("settings.suggest_station.issue_title", comment: "") + " ") {
            Link(destination: url) {
                Label {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("settings.suggest_station.title")
                            .foregroundStyle(.primary)
                        Text("settings.suggest_station.detail")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "mappin.and.ellipse")
                }
            }
        }
    }
}

/// «Crediti e licenze» of «Informazioni»: the emission factors of the CO2 estimate and their source.
struct CreditsRows: View {
    var body: some View {
        Text("settings.about.credits")
            .font(.subheadline.weight(.semibold))
        Text(PersonalTexts.co2Credits)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .textSelection(.enabled)
    }
}

extension AppModel {
    /// The catalogue station of an id, or nil.
    func catalogStation(id: String) -> Station? {
        guard case let .available(catalog) = catalogState else { return nil }
        return catalog.station(id: id)
    }

    /// The catalogue station a published name or alias names, case and accents aside; nil when none.
    func catalogStation(named name: String) -> Station? {
        guard case let .available(catalog) = catalogState else { return nil }
        let wanted = name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "it_IT"))
        return catalog.offeredStations.first { station in
            ([station.name] + station.aliases).contains {
                $0.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "it_IT")) == wanted
            }
        }
    }
}

/// Android's theme choice over the whole app: «Sistema» follows iOS.
struct AppThemeModifier: ViewModifier {
    @EnvironmentObject private var personal: PersonalModel

    func body(content: Content) -> some View {
        content.preferredColorScheme(personal.theme.colorScheme)
    }
}
