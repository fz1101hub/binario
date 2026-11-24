import SwiftUI
import BinarioKit

// The views of Android's smaller features on the iPhone: the CO2 estimate, the shares, the personal
// punctuality, the official pages of a train, «Orari salvati», «Tipi di treno», the favourite and
// nearby stations and Sala Blu.

// MARK: CO2

/// «CO₂ stimata: 4,8 kg (auto ~31 kg, aereo ~74 kg)» under a result row, read in words.
struct Co2Line: View {
    let estimate: Co2Estimate

    var body: some View {
        Label(PersonalTexts.co2Line(estimate), systemImage: "leaf")
            .font(.caption)
            .foregroundStyle(.secondary)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(PersonalTexts.co2Spoken(estimate)))
    }
}

/// «CO₂ stimata» of a train detail, with Android's note on what the estimate is.
struct Co2Section: View {
    let estimate: Co2Estimate

    var body: some View {
        Section {
            Text(PersonalTexts.co2Line(estimate))
                .accessibilityLabel(Text(PersonalTexts.co2Spoken(estimate)))
            Text("result.co2.note")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Label("result.co2.title", systemImage: "leaf")
        }
    }
}

extension AppModel {
    /// The estimate between two stations of the catalogue, from their positions; nil when one has
    /// none or the trip is too short (Android's `rememberCo2` of a direct train).
    func co2Estimate(from origin: StationIdentity, to destination: StationIdentity) -> Co2Estimate? {
        func point(_ identity: StationIdentity) -> Co2Point? {
            let found = self.station(for: identity)
            guard let latitude = found.latitude, let longitude = found.longitude else { return nil }
            return Co2Point(latitude: latitude, longitude: longitude)
        }
        return Co2Estimator.estimate(points: [(point(origin), point(destination))])
    }
}

// MARK: Shares

/// «Condividi» of a train detail: the system share sheet with Android's text.
struct TrainShareButton: View {
    let title: String
    let text: String

    var body: some View {
        ShareLink(item: text, subject: Text(verbatim: title), preview: SharePreview(title)) {
            Label("action.share", systemImage: "square.and.arrow.up")
        }
    }
}

/// «Salva orario» and «Condividi» under the results (Android's `ResultsActions`): the list as shown,
/// saved in «Orari salvati» or shared as text.
struct ResultsActionsSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel
    let snapshot: JourneySnapshot
    let services: [TrainService]
    @State private var savedID: String?

    var body: some View {
        let rows = services.map { PersonalTexts.timetableRow($0, live: model.resultLive[$0.id], source: snapshot.provenance.sourceLabel) }
        // The return of a round trip, when the planner searched one.
        let back = personal.plannerReturn.map { returned in
            returned.services.map { PersonalTexts.timetableRow($0, live: nil, source: returned.provenance.sourceLabel) }
        } ?? []
        let date = ItalianWallClock.date(of: snapshot.query.departure) ?? PersonalModel.today()
        let from = LiveTexts.clock(snapshot.query.departure)
        let share = PersonalTexts.listShare(
            origin: snapshot.query.origin.name,
            destination: snapshot.query.destination.name,
            dateLabel: PersonalTexts.searchDate(date, from: from),
            rows: rows,
            returnRows: back
        )
        Section {
            Button {
                save(rows: rows, returnRows: back, date: date, from: from)
            } label: {
                Label("result.save_timetable", systemImage: savedID == nil ? "square.and.arrow.down" : "checkmark")
            }
            .disabled(savedID != nil || rows.isEmpty)
            if savedID != nil {
                Text("result.timetable_saved")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ShareLink(item: share.text, subject: Text(verbatim: share.title), preview: SharePreview(share.title)) {
                Label("action.share", systemImage: "square.and.arrow.up")
            }
        }
        // A new search is a new list to save.
        .onChange(of: snapshot.generatedAt) { _, _ in savedID = nil }
    }

    private func save(rows: [TimetableRow], returnRows: [TimetableRow], date: PlannedLocalDate, from: String) {
        let id = UUID().uuidString
        personal.saveTimetable(SavedTimetable(
            id: id,
            name: "\(snapshot.query.origin.name) → \(snapshot.query.destination.name)",
            origin: snapshot.query.origin.name,
            destination: snapshot.query.destination.name,
            serviceDate: date,
            fromTime: from,
            savedAt: Date(),
            rows: rows,
            returnRows: returnRows
        ))
        savedID = id
        DeparturesTexts.announce(String(localized: "result.timetable_saved"))
    }
}


// MARK: Puntualità personale

/// «Puntualità» of a train detail, from the observations this iPhone recorded for the train number
/// (Android's `PunctualityCard`): mean delay, share on time and over 15 minutes, the stop where the
/// delay grows most, the distribution and the history by date. Nothing here is fetched or shared.
struct PersonalPunctualitySection: View {
    @EnvironmentObject private var personal: PersonalModel
    let number: String
    @State private var showsAll = false

    var body: some View {
        if let stats = personal.punctualityStats(number: number) {
            Section {
                Text("result.punctuality.based_on_device")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(PersonalTexts.observations(stats.count))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Group {
                    if let average = stats.averageDelayMinutes {
                        Text(PersonalTexts.format("result.punctuality.average_delay", PersonalTexts.minutes(average)))
                    } else {
                        Text("result.punctuality.average_unavailable")
                    }
                }
                .font(.body.weight(.semibold))
                Text(PersonalTexts.format("result.punctuality.on_time_share", stats.onTimePercent))
                Text(PersonalTexts.format("result.punctuality.late_share", stats.latePercent))
                if stats.cancelledCount > 0 {
                    Text(PersonalTexts.format("result.punctuality.cancelled_count", stats.cancelledCount))
                }
                if let worst = stats.worstStop {
                    Text(PersonalTexts.format("result.punctuality.worst_stop", worst.station, PersonalTexts.minutes(worst.averageIncreaseMinutes)))
                }
                PunctualityDistribution(distribution: stats.distribution)
                Text("result.punctuality.recent_title")
                    .font(.subheadline.weight(.semibold))
                let collapsed = SharedData.PersonalPunctuality.historyCollapsed
                ForEach(showsAll ? stats.history : Array(stats.history.prefix(collapsed)), id: \.self) { observation in
                    LabeledContent {
                        Text(PersonalTexts.observationState(observation))
                            .font(.subheadline.weight(.semibold))
                    } label: {
                        Text(NoticeTexts.day(observation.serviceDate, Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: ItalianWallClock.timeZone)))
                            .font(.subheadline)
                    }
                    .accessibilityElement(children: .combine)
                }
                if !showsAll, stats.history.count > collapsed {
                    Button("result.punctuality.show_all") { showsAll = true }
                }
                Button("result.punctuality.clear", role: .destructive) {
                    personal.clearPunctuality(number: number)
                }
            } header: {
                Label("result.punctuality.title", systemImage: "clock.arrow.circlepath")
            }
        }
    }
}

/// «Distribuzione dei ritardi»: one bar per band, proportional to the largest, with its count.
private struct PunctualityDistribution: View {
    let distribution: [PunctualityBucket: Int]

    var body: some View {
        let largest = max(distribution.values.max() ?? 1, 1)
        VStack(alignment: .leading, spacing: 4) {
            Text("result.punctuality.distribution_title")
                .font(.subheadline.weight(.semibold))
            ForEach(PunctualityBucket.allCases, id: \.self) { bucket in
                let count = distribution[bucket] ?? 0
                HStack(spacing: 8) {
                    Text(PersonalTexts.bucketLabel(bucket))
                        .font(.caption)
                        .frame(width: 112, alignment: .leading)
                    GeometryReader { proxy in
                        RoundedRectangle(cornerRadius: 4)
                            .fill(color(bucket))
                            .frame(width: proxy.size.width * CGFloat(count) / CGFloat(largest), height: 10)
                    }
                    .frame(height: 10)
                    Text(verbatim: "\(count)")
                        .font(.caption.weight(.semibold).monospacedDigit())
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: "\(PersonalTexts.bucketLabel(bucket)): \(count)"))
            }
        }
    }

    private func color(_ bucket: PunctualityBucket) -> Color {
        switch bucket {
        case .onTime: BinarioPalette.brandGreen
        case .upTo15: Color.orange
        case .upTo30, .over30: BinarioPalette.statusError
        case .cancelled: Color.secondary
        }
    }
}

extension PersonalModel {
    /// Records the run a source published for its service day, once it has an observation of the
    /// last stop or is cancelled (Android's `PunctualityHistory.observationOf`): one per train and day.
    func record(_ status: AppModel.TrainLiveStatus) {
        let run = status.run
        let cancelled = run.cancellation == .full || run.state == .cancelled
        guard cancelled || run.operationalStatusVerified,
              let serviceDate = ItalianWallClock.date(of: run.scheduledDeparture) else { return }
        let verified = run.stops.filter { $0.operationalStatusVerified && !$0.skipped }
        guard let observation = PunctualityHistory.observation(
            number: run.number,
            operatorName: run.operatorName,
            serviceDate: serviceDate,
            today: Self.today(),
            delayMinutes: run.delayMinutes ?? 0,
            cancelled: cancelled,
            stops: verified.map { PunctualityStopDelay(station: $0.stationName, delayMinutes: $0.delayMinutes) }
        ) else { return }
        record(observation)
    }
}

// MARK: Official pages of a train

/// «Apri sul sito ufficiale (Trenitalia)» with Android's note, and «Bici a bordo: condizioni di
/// Trenord» when the timetable does not say whether bikes are allowed. Links only.
struct OfficialPagesSection: View {
    let service: TrainService

    var body: some View {
        let site = service.isUrban ? nil : OfficialLinks.purchaseSite(operatorName: service.operatorName)
        let bikes = service.bikesAllowed == nil && !service.isUrban
            ? OfficialLinks.operatorOf(operatorName: service.operatorName).flatMap { links in links.url(.bikes).map { (links.name, $0) } }
            : nil
        if site != nil || bikes != nil {
            Section {
                if let site, let buy = site.url(.buy) {
                    Link(destination: buy) {
                        Label(PersonalTexts.format("result.official_site.open", site.name), systemImage: "arrow.up.right.square")
                    }
                    Text("result.official_site.note")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let bikes {
                    Link(destination: bikes.1) {
                        Label(PersonalTexts.format("result.bikes.conditions", bikes.0), systemImage: "bicycle")
                    }
                }
            }
        }
    }
}

// MARK: Orari salvati

/// «Orari salvati» of Per te: the saved lists, newest first; a tap opens one, read-only.
struct SavedTimetablesSection: View {
    @EnvironmentObject private var personal: PersonalModel

    var body: some View {
        Section {
            if personal.savedTimetables.isEmpty {
                Text("saved.timetables.empty")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(personal.savedTimetables) { timetable in
                NavigationLink {
                    SavedTimetableView(id: timetable.id)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(verbatim: timetable.name)
                            .font(.headline)
                        Text(verbatim: "\(PersonalTexts.searchDate(timetable.serviceDate, from: timetable.fromTime)) · "
                            + String.localizedStringWithFormat(NSLocalizedString("saved.timetables.solutions", comment: ""), timetable.solutionCount))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .onDelete { offsets in
                let ids = offsets.compactMap { personal.savedTimetables.indices.contains($0) ? personal.savedTimetables[$0].id : nil }
                for id in ids { delete(id) }
            }
        } header: {
            Label("saved.timetables.title", systemImage: "tray.full")
        }
    }

    private func delete(_ id: String) {
        guard let name = personal.savedTimetables.first(where: { $0.id == id })?.name else { return }
        personal.deleteTimetable(id: id)
        DeparturesTexts.announce(PersonalTexts.format("saved.timetables.deleted", name))
    }
}

/// One saved list, read-only and marked as not updated, with «Rinomina», «Condividi» and «Elimina».
struct SavedTimetableView: View {
    @EnvironmentObject private var personal: PersonalModel
    @Environment(\.dismiss) private var dismiss
    let id: String
    @State private var renaming = false
    @State private var newName = ""

    var body: some View {
        if let timetable = personal.savedTimetables.first(where: { $0.id == id }) {
            let share = PersonalTexts.listShare(
                origin: timetable.origin,
                destination: timetable.destination,
                dateLabel: PersonalTexts.searchDate(timetable.serviceDate, from: timetable.fromTime),
                rows: timetable.rows,
                returnRows: timetable.returnRows,
                savedAt: timetable.savedAt
            )
            List {
                Section {
                    Text(verbatim: "\(timetable.origin) → \(timetable.destination)")
                        .font(.headline)
                    Text(PersonalTexts.searchDate(timetable.serviceDate, from: timetable.fromTime))
                    Label(PersonalTexts.format("result.share.saved_not_updated", PersonalTexts.savedAt(timetable.savedAt)), systemImage: "clock.badge.exclamationmark")
                        .font(.footnote)
                    Text("saved.timetables.offline_notice")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                SavedRowsSection(rows: timetable.rows, titleKey: nil)
                if !timetable.returnRows.isEmpty {
                    SavedRowsSection(rows: timetable.returnRows, titleKey: "result.share.return_title")
                }
            }
            .navigationTitle(Text(verbatim: timetable.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            newName = timetable.name
                            renaming = true
                        } label: {
                            Label("action.rename", systemImage: "pencil")
                        }
                        ShareLink(item: share.text, subject: Text(verbatim: share.title), preview: SharePreview(share.title)) {
                            Label("action.share", systemImage: "square.and.arrow.up")
                        }
                        Button(role: .destructive) {
                            personal.deleteTimetable(id: id)
                            DeparturesTexts.announce(PersonalTexts.format("saved.timetables.deleted", timetable.name))
                            dismiss()
                        } label: {
                            Label("action.delete", systemImage: "trash")
                        }
                    } label: {
                        Label("saved.timetables.title", systemImage: "ellipsis.circle")
                    }
                }
            }
            .alert("saved.timetables.rename_title", isPresented: $renaming) {
                TextField("saved.timetables.name_label", text: $newName)
                Button("action.save") { personal.renameTimetable(id: id, name: newName) }
                Button("action.cancel", role: .cancel) {}
            }
        }
    }
}

/// The rows of a saved list as they were saved: times, trains, changes and platform.
private struct SavedRowsSection: View {
    let rows: [TimetableRow]
    let titleKey: LocalizedStringKey?

    var body: some View {
        Section {
            ForEach(rows, id: \.self) { row in
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: PersonalTexts.times(row))
                        .font(.headline.monospacedDigit())
                    if !row.trains.isEmpty {
                        Text(verbatim: row.trains)
                    }
                    ForEach(row.transfers, id: \.self) { transfer in
                        Text(verbatim: transfer)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if let platform = PersonalTexts.platform(row) {
                        Text(verbatim: platform)
                            .font(.caption)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        } header: {
            if let titleKey { Text(titleKey) }
        }
    }
}

// MARK: Tipi di treno

/// «Tipi di treno» of a station board: what is hidden, «Mostra tutti», and the sheet that chooses.
struct TrainTypesControl: View {
    @EnvironmentObject private var personal: PersonalModel
    let station: StationIdentity
    /// Trains of the board the choice hides now.
    let hiddenCount: Int
    @State private var choosing = false

    var body: some View {
        let excluded = personal.excludedTrainTypes(at: station.id)
        VStack(alignment: .leading, spacing: 4) {
            Button {
                choosing = true
            } label: {
                Label("departures.types.action", systemImage: "line.3.horizontal.decrease.circle")
            }
            if excluded.isEmpty {
                Text("departures.types.all")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text(PersonalTexts.format("departures.types.hidden",
                                          TrainTypeGroup.allCases.filter { excluded.contains($0) }.map(PersonalTexts.groupLabel).joined(separator: ", ")))
                    .font(.footnote)
                if hiddenCount > 0 {
                    Text(PersonalTexts.hiddenTrains(hiddenCount))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Button("departures.types.show_all") {
                    personal.setExcludedTrainTypes([], at: station.id)
                }
                .buttonStyle(.borderless)
            }
        }
        .sheet(isPresented: $choosing) {
            TrainTypesSheet(station: station)
        }
    }
}

private struct TrainTypesSheet: View {
    @EnvironmentObject private var personal: PersonalModel
    @Environment(\.dismiss) private var dismiss
    let station: StationIdentity

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(TrainTypeGroup.allCases, id: \.self) { group in
                        Toggle(PersonalTexts.groupLabel(group), isOn: binding(group))
                    }
                } footer: {
                    Text("departures.types.sheet_description")
                }
            }
            .navigationTitle(Text(PersonalTexts.format("departures.types.sheet_title", station.name)))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("action.done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    /// On: the type is shown; off: hidden at this station.
    private func binding(_ group: TrainTypeGroup) -> Binding<Bool> {
        Binding(
            get: { !personal.excludedTrainTypes(at: station.id).contains(group) },
            set: { shown in
                var excluded = personal.excludedTrainTypes(at: station.id)
                if shown { excluded.remove(group) } else { excluded.insert(group) }
                personal.setExcludedTrainTypes(excluded, at: station.id)
            }
        )
    }
}

// MARK: Stazioni preferite e vicine

/// The star of a board: adds the station to «Stazioni preferite» or removes it.
struct FavouriteStationButton: View {
    @EnvironmentObject private var personal: PersonalModel
    let station: StationIdentity

    var body: some View {
        let favourite = personal.isFavourite(station)
        Button {
            personal.toggleFavourite(station)
        } label: {
            Label(
                PersonalTexts.format(favourite ? "departures.favourite.remove" : "departures.favourite.add", station.name),
                systemImage: favourite ? "star.fill" : "star"
            )
        }
    }
}

/// «Stazioni preferite»: a tap opens the station's board.
struct FavouriteStationsSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel

    var body: some View {
        if !personal.favouriteStations.isEmpty {
            Section("saved.stations.title") {
                ForEach(personal.favouriteStations) { station in
                    Button {
                        model.selectBoardStation(station)
                        model.selectedTab = .departures
                    } label: {
                        Label {
                            Text(verbatim: station.name)
                                .foregroundStyle(.primary)
                        } icon: {
                            Image(systemName: "star.fill")
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            personal.toggleFavourite(station)
                        } label: {
                            Label(PersonalTexts.format("departures.favourite.remove", station.name), systemImage: "star.slash")
                        }
                    }
                }
            }
        }
    }
}

/// «Stazioni più vicine»: one position on a tap, the eight nearest stations of the catalogue by
/// straight-line distance, a tap opens a board. The position is kept nowhere.
struct NearbyStationsSection: View {
    @EnvironmentObject private var model: AppModel
    @State private var locator: OneShotLocation?
    @State private var locating = false
    @State private var stations: [NearbyStation] = []
    @State private var message: String?

    var body: some View {
        Section("departures.nearby.title") {
            Button(action: locate) {
                if locating {
                    HStack {
                        ProgressView()
                        Text("planner.nearest.locating")
                    }
                } else {
                    Label("departures.nearby.find", systemImage: "location.magnifyingglass")
                }
            }
            .disabled(locating)
            ForEach(stations, id: \.station.id) { nearby in
                Button {
                    model.selectBoardStation(StationIdentity(nearby.station))
                } label: {
                    LabeledContent {
                        Text(PersonalTexts.distance(nearby.distanceKilometres * 1_000))
                    } label: {
                        Text(verbatim: nearby.station.name)
                            .foregroundStyle(.primary)
                    }
                }
            }
            if let message {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func locate() {
        guard !locating else { return }
        locating = true
        message = nil
        let locator = self.locator ?? OneShotLocation()
        self.locator = locator
        Task {
            let text: String
            switch await locator.locate() {
            case .denied:
                text = NSLocalizedString("planner.nearest.denied", comment: "")
            case .unavailable:
                text = NSLocalizedString("planner.nearest.unavailable", comment: "")
            case let .located(latitude, longitude):
                stations = await model.nearbyStations(latitude: latitude, longitude: longitude)
                text = NSLocalizedString(stations.isEmpty ? "planner.nearest.none" : "departures.nearby.sorted", comment: "")
            }
            locating = false
            message = text
            DeparturesTexts.announce(text)
        }
    }
}

extension AppModel {
    /// The eight nearest stations of the catalogue to one position (Android's `nearbyStations`),
    /// computed on the iPhone; the position is kept nowhere.
    func nearbyStations(latitude: Double, longitude: Double) async -> [NearbyStation] {
        guard case let .available(catalog) = catalogState else { return [] }
        return await Task.detached(priority: .userInitiated) {
            Array(StationProximity.nearby(catalog.offeredStations, latitude: latitude, longitude: longitude).prefix(8))
        }.value
    }
}

// MARK: Sala Blu

/// «Assistenza Sala Blu» under a board of a station of the Italian network: RFI's page and the two
/// national numbers, opened only on a tap by the browser and the phone's dialer.
struct SalaBluSection: View {
    let station: Station

    var body: some View {
        if OfficialLinks.SalaBlu.applies(to: station) {
            Section("departures.sala_blu.title") {
                Text("departures.sala_blu.description")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if let page = OfficialLinks.SalaBlu.page {
                    Link(destination: page) {
                        Label("departures.sala_blu.page", systemImage: "figure.roll")
                    }
                }
                if let call = OfficialLinks.SalaBlu.dialURL(OfficialLinks.SalaBlu.tollFree) {
                    Link(destination: call) {
                        Label(PersonalTexts.format("departures.sala_blu.call_toll_free", OfficialLinks.SalaBlu.tollFree), systemImage: "phone")
                    }
                }
                if let call = OfficialLinks.SalaBlu.dialURL(OfficialLinks.SalaBlu.national) {
                    Link(destination: call) {
                        Label(PersonalTexts.format("departures.sala_blu.call_national", OfficialLinks.SalaBlu.national), systemImage: "phone")
                    }
                }
            }
        }
    }
}
