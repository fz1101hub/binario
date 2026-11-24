import SwiftUI
import BinarioKit

struct PlannerView: View {
    @EnvironmentObject private var model: AppModel
    /// The journeys with changes, searched next to the direct trains (JourneysModel.swift).
    @EnvironmentObject private var journeys: JourneysModel
    @EnvironmentObject private var notices: RailNoticesModel
    @EnvironmentObject private var personal: PersonalModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var origin: Station?
    @State private var destination: Station?
    @State private var departure = Date()
    /// False until the user picks a time: the planner then searches from the current time, so a
    /// tab left open does not keep asking (and telling the Watch) about trains that have left.
    @State private var departureEdited = false
    /// A saved route arrived while the timetables were still loading.
    @State private var searchWhenScheduleIsReady = false
    @State private var validationKey: String?
    @State private var isSearching = false
    @State private var searchRequestID = 0
    /// The minute the results are read at: «Più puntuale» never suggests a train that has left.
    @State private var clock = Date()
    /// Android's «Andata e ritorno»: the return's departure and its trains (PlannerExtras.swift).
    @State private var roundTrip = false
    @State private var returnDeparture = Date().addingTimeInterval(4 * 3_600)
    @State private var returnResult: JourneyResult?

    var body: some View {
        Form {
            Section {
                Label {
                    Text("planner.intro.title")
                } icon: {
                    Image(systemName: "train.side.front.car")
                        .foregroundStyle(BinarioPalette.brandGreen)
                }
                .font(.headline)
                Text("planner.intro.body")
                    .foregroundStyle(.secondary)
                if !model.includedPacks.isEmpty {
                    Text("\(String(localized: "planner.sources.footer")) \(model.includedFeedAttributions)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            switch model.catalogState {
            case .loading:
                Section {
                    HStack {
                        ProgressView()
                        Text("catalog.loading")
                    }
                }
            case .available:
                // Android's «Treno» and «Stazione» modes (PlannerExtras.swift).
                PlannerModesSection()
                // A typed or dictated route fills the form (EXPO_PORT_PLAN I6).
                RouteAssistSection(origin: originSelection, destination: destinationSelection, departure: assistedDeparture)
                Section {
                    StationPickerField(titleKey: "planner.origin", selection: originSelection)
                    StationPickerField(titleKey: "planner.destination", selection: destinationSelection)
                    // Results list times in the timetable zone: pick them in the same zone.
                    DatePicker("planner.departure", selection: departureSelection)
                        .environment(\.timeZone, AppModel.timetableTimeZone)
                    if AppModel.needsTimetableZoneCaption(at: departure) {
                        Text("planner.departure.italian_time")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    // The changes filter of Android's planner: it filters the journeys shown.
                    Picker("planner.changes.title", selection: $journeys.changeFilter) {
                        ForEach(JourneyChangeFilter.allCases, id: \.self) { filter in
                            Text(verbatim: JourneyTexts.changeFilter(filter))
                                .accessibilityLabel(Text(verbatim: JourneyTexts.changeFilterAccessible(filter)))
                                .tag(filter)
                        }
                    }
                    RoundTripFields(roundTrip: $roundTrip, returnDeparture: $returnDeparture)
                }

                if model.scheduleState == .loading {
                    Section {
                        HStack {
                            ProgressView()
                            Text("schedule.loading")
                        }
                    }
                } else if model.scheduleState == .unavailable {
                    Section {
                        Label("schedule.unavailable", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    Button(action: swapStations) {
                        Label("planner.swap", systemImage: "arrow.up.arrow.down")
                    }
                    .accessibilityLabel(Text("accessibility.swap_route"))

                    Button(action: search) {
                        if isSearching {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            // The prominent fill is AccentColor: white text fails on its
                            // light dark-mode green, so the label takes the paired token.
                            Text("planner.search")
                                .foregroundStyle(canSearch ? BinarioPalette.onAccent : Color.secondary)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canSearch)

                    // A route already saved shows the filled star instead of a button that does nothing.
                    Button(action: save) {
                        if isSaved {
                            Label("accessibility.saved_route", systemImage: "star.fill")
                        } else {
                            Label("planner.save", systemImage: "star")
                        }
                    }
                    .disabled(origin == nil || destination == nil || isSaved)
                }

                if let validationKey {
                    Section { Text(LocalizedStringKey(validationKey)).foregroundStyle(BinarioPalette.statusError) }
                }

                // Android's «Recenti e preferite» before a search (PersonalLists.swift).
                if model.latestSnapshot == nil, model.searchState == .idle {
                    RecentAndFavouriteRoutesSection()
                }

                if !model.airportLinks.isEmpty, model.latestSnapshot == nil, model.searchState == .idle {
                    AirportLinksSection(links: model.airportLinks, search: searchAirport)
                }

                // A full foreign timetable not on this iPhone would serve the search (design §4.4).
                if let offer = model.plannerForeignOffer {
                    ForeignOfferSection(offer: offer)
                }

                if let snapshot = model.latestSnapshot {
                    AvailableResultView(snapshot: snapshot, now: clock)
                    // Android's board of a saved route: the works its notices name, nothing when none does.
                    RouteWorksSection(routes: resultRoutes, scope: .thisRoute)
                    // «Treni in movimento» of the route (LiveTrainMapView.swift).
                    RouteLiveTrainsEntry(snapshot: snapshot)
                    if roundTrip, let returnResult {
                        ReturnResultsSection(result: returnResult)
                    }
                } else if case let .unavailable(reason) = model.searchState {
                    // No direct train: the journeys with changes may still get there.
                    if journeys.rows.isEmpty {
                        UnavailableResultView(reason: reason)
                    }
                    JourneysOnlyResultView()
                }
            case .unavailable:
                ContentUnavailableView(
                    "catalog.unavailable.title",
                    systemImage: "exclamationmark.triangle",
                    description: Text("catalog.unavailable.body")
                )
            }
        }
        .navigationTitle("planner.title")
        .loadsRouteWorks(resultRoutes, model: model, notices: notices)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { followCurrentTime() }
        }
        .onChange(of: model.scheduleState) { _, state in
            guard state == .available, searchWhenScheduleIsReady else { return }
            searchWhenScheduleIsReady = false
            if canSearch { search() }
        }
        // A full foreign timetable installed or deleted: the route on screen is searched again.
        .onChange(of: model.timetablesGeneration) { _, _ in
            guard model.latestSnapshot != nil || model.searchState != .idle, canSearch else { return }
            search()
        }
        // Only after a search found nothing (owner decision Q3); «Non ora» holds for this launch.
        .alert(
            Text(verbatim: model.foreignDialogPack.flatMap { ForeignTexts.prompt($0) } ?? ""),
            isPresented: foreignDialogShown,
            presenting: model.foreignDialogPack
        ) { pack in
            Button("schedule.foreign.download") { model.downloadForeignPack(pack.sourceID) }
            Button("schedule.foreign.not_now", role: .cancel) { model.declineForeignPack(pack.sourceID) }
        } message: { _ in
            Text("schedule.foreign.prompt_detail")
        }
        .task(id: model.plannerRequest?.id) { applyPlannerRequest() }
        .task { await followTheClock() }
        // The live values of the results are asked only while the planner is on screen.
        .onAppear { model.plannerLiveDidAppear() }
        .onDisappear { model.plannerLiveDidDisappear() }
    }

    private var foreignDialogShown: Binding<Bool> {
        Binding(
            get: { model.foreignDialogPack != nil },
            set: { shown in
                if !shown { model.dismissForeignDialog() }
            }
        )
    }

    private var canSearch: Bool {
        origin != nil && destination != nil && !isSearching && model.scheduleState == .available
    }

    // Only the user's own edits invalidate the result: the planner also moves the time to now
    // and fills a saved route itself, and must not revoke the Watch snapshot when it does.
    private var originSelection: Binding<Station?> {
        Binding(
            get: { origin },
            set: { station in
                guard station?.id != origin?.id else { return }
                origin = station
                inputDidChange()
            }
        )
    }

    private var destinationSelection: Binding<Station?> {
        Binding(
            get: { destination },
            set: { station in
                guard station?.id != destination?.id else { return }
                destination = station
                inputDidChange()
            }
        )
    }

    private var departureSelection: Binding<Date> {
        Binding(
            get: { departure },
            set: { date in
                guard date != departure else { return }
                departure = date
                departureEdited = true
                inputDidChange()
            }
        )
    }

    /// The departure a dictation sets, as if the user had picked it.
    private var assistedDeparture: Binding<Date?> {
        Binding(
            get: { departure },
            set: { date in
                if let date { departureSelection.wrappedValue = date }
            }
        )
    }

    private func swapStations() {
        let changesInput = origin?.id != destination?.id
        (origin, destination) = (destination, origin)
        validationKey = nil
        if changesInput { inputDidChange() }
    }

    /// Back in the app with the default time: move it to now and, when a result is on screen,
    /// search again so the iPhone and the Watch list the trains still to come.
    private func followCurrentTime() {
        // The clock's task may have slept through the time in the background.
        clock = Date()
        guard !departureEdited else { return }
        departure = Date()
        if model.latestSnapshot != nil || model.searchState != .idle, canSearch {
            search()
        }
    }

    /// Moves `clock` at every new minute while the planner is on screen (the task ends when it
    /// leaves the screen and starts again, from the current time, when it comes back).
    private func followTheClock() async {
        while !Task.isCancelled {
            let current = Date()
            clock = current
            let seconds = 60 - current.timeIntervalSince1970.truncatingRemainder(dividingBy: 60)
            try? await Task.sleep(nanoseconds: UInt64((seconds + 0.5) * 1_000_000_000))
        }
    }

    /// A saved route tapped in its tab: fill it in and search it from now.
    private func applyPlannerRequest() {
        guard let request = model.takePlannerRequest() else { return }
        origin = model.station(for: request.origin)
        destination = model.station(for: request.destination)
        if let chosen = request.departure {
            // «Collegamenti diretti» for another day: that day, at the first departure.
            departure = chosen
            departureEdited = true
        } else {
            departure = Date()
            departureEdited = false
        }
        inputDidChange()
        if canSearch {
            search()
        } else {
            searchWhenScheduleIsReady = true
        }
    }

    private func inputDidChange() {
        searchRequestID &+= 1
        validationKey = nil
        isSearching = false
        returnResult = nil
        personal.plannerReturn = nil
        model.invalidateSearchResult()
        journeys.reset()
    }

    private func makeQuery() -> JourneyQuery? {
        guard let origin, let destination else {
            validationKey = "planner.selection_required"
            return nil
        }
        guard origin.id != destination.id else {
            validationKey = "planner.same_station"
            return nil
        }
        do {
            return try JourneyQuery(
                origin: StationIdentity(origin),
                destination: StationIdentity(destination),
                departure: departure
            )
        } catch {
            validationKey = "planner.selection_required"
            return nil
        }
    }

    private func search() {
        if !departureEdited { departure = Date() }
        guard let query = makeQuery() else { return }
        validationKey = nil
        // «Ricerca recente», as Android records every route searched.
        personal.recordRecent(origin: query.origin, destination: query.destination)
        searchRequestID &+= 1
        let requestID = searchRequestID
        isSearching = true
        journeys.search(query, repository: model.repository, catalog: model.catalogState, stats: model.reliabilityStats)
        Task {
            await model.run(query: query)
            guard requestID == searchRequestID else { return }
            await searchReturn(after: query)
            guard requestID == searchRequestID else { return }
            isSearching = false
        }
    }

    /// «Andata e ritorno»: the way back from the destination at the return's time, which must follow
    /// the outbound departure (Android's `planner_return_after_outbound`).
    private func searchReturn(after query: JourneyQuery) async {
        returnResult = nil
        personal.plannerReturn = nil
        guard roundTrip else { return }
        guard returnDeparture > query.departure else {
            validationKey = "planner.return.after_outbound"
            return
        }
        guard let back = try? JourneyQuery(origin: query.destination, destination: query.origin, departure: returnDeparture, direction: .return) else { return }
        let result = await model.repository.journeys(for: back)
        returnResult = result
        if case let .available(snapshot) = result { personal.plannerReturn = snapshot }
    }

    private func save() {
        guard let query = makeQuery() else { return }
        validationKey = nil
        // A refusal (the limit, the storage) is the alert of RootView, on this tab too.
        model.save(query: query)
        if isSaved { DeparturesTexts.announce(String(localized: "accessibility.saved_route")) }
    }

    /// The saved route the results are for, if any: a search not saved has no works section, as on Android.
    private var resultRoutes: [SavedRoute] {
        guard let query = model.latestSnapshot?.query else { return [] }
        let id = SavedRoute.routeIdentifier(origin: query.origin, destination: query.destination)
        return model.savedRoutes.filter { $0.id == id }
    }

    /// The route of the form is among the saved routes.
    private var isSaved: Bool {
        guard let origin, let destination else { return false }
        let id = SavedRoute.routeIdentifier(origin: StationIdentity(origin), destination: StationIdentity(destination))
        return model.savedRoutes.contains { $0.id == id }
    }

    /// An airport link: fill the form and search it from now, as a saved route does.
    private func searchAirport(from origin: Station, to destination: Station) {
        model.openPlanner(origin: StationIdentity(origin), destination: StationIdentity(destination))
    }
}

/// «Collegamenti con gli aeroporti»: a tap searches towards the airport, the button beside it the
/// way back. Station names as the catalogue has them; no service brand.
private struct AirportLinksSection: View {
    let links: [AirportLink]
    let search: @MainActor (Station, Station) -> Void

    var body: some View {
        Section("planner.airports.title") {
            ForEach(links) { link in
                HStack {
                    Button {
                        search(link.city, link.airport)
                    } label: {
                        Label {
                            Text(verbatim: "\(link.city.name) → \(link.airport.name)")
                        } icon: {
                            Image(systemName: "airplane.departure")
                        }
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text(ReliabilityTexts.airportSearch(from: link.city.name, to: link.airport.name, cityLine: AirportRoutes.byCityLine(link.route))))
                    Spacer()
                    Button {
                        search(link.airport, link.city)
                    } label: {
                        Image(systemName: "arrow.uturn.backward")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text(ReliabilityTexts.airportSearch(from: link.airport.name, to: link.city.name, cityLine: AirportRoutes.byCityLine(link.route))))
                }
            }
        }
    }
}

private struct AvailableResultView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var journeys: JourneysModel
    let snapshot: JourneySnapshot
    /// The current minute: a listed train is gone a minute after its departure (scheduled, or
    /// delayed by the minutes a live source publishes).
    let now: Date

    var body: some View {
        // The statistics of this result: computed by the model when the search or the file arrives.
        let reliability = model.resultReliability
        let measuredTrains = reliability?.byServiceID ?? [:]
        // «Più puntuale: …» under a train often late at the destination: another listed train
        // leaving within 30 minutes, still to be taken (not cancelled, its live delay counted),
        // clearly more often on time there.
        let alternatives = ReliableAlternatives.find(in: snapshot, reliability: measuredTrains, live: model.resultLive, now: now)
        // Android's CO2 estimate of a direct train: the same stations for every row.
        let co2 = model.co2Estimate(from: snapshot.query.origin, to: snapshot.query.destination)
        // The direct trains and the journeys with changes in one list, with Android's chips.
        let merged = mergedEntries
        let scores = reliabilityScores(measuredTrains)
        let shown = JourneyResultsMerge.apply(merged.entries, filter: journeys.filter, preferences: journeys.preferences, reliability: scores)
        Section("result.title") {
            if let route = reliability?.route {
                RoutePunctualityView(route: route)
            }
            JourneyFilterChips(
                selection: journeys.filter, counts: JourneyResultsMerge.counts(merged.entries), showsMostReliable: !scores.isEmpty
            ) { journeys.filter = $0 }
            JourneyActiveFiltersView(hiddenTrains: snapshot.services.count - JourneyResultsMerge.shownServices(snapshot.services, preferences: journeys.preferences).count)
            if let first = merged.firstNextDay {
                Text(verbatim: JourneyTexts.noMoreToday(first))
                    .font(.footnote.weight(.medium))
            } else if let first = merged.noTrainsUntil {
                Text(verbatim: JourneyTexts.noTrainsUntil(first))
                    .font(.footnote.weight(.medium))
            }
            if shown.isEmpty, !merged.entries.isEmpty {
                Text(verbatim: JourneyTexts.empty(.noSolution, hasOptions: true))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(shown) { entry in
                switch entry {
                case let .train(service, lastOfDay):
                    let alternative = alternatives[service.id]
                    let live = model.resultLive[service.id]
                    NavigationLink {
                        TrainDetailView(service: service, query: snapshot.query, boardLive: live)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            ResultRow(service: service, reliability: measuredTrains[service.id], live: live, co2: co2)
                            if lastOfDay {
                                LastOfDayMark()
                            }
                        }
                    }
                    .accessibilityHint(Text("accessibility.open_train_detail"))
                    .listRowSeparator(alternative == nil ? .automatic : .hidden, edges: .bottom)
                    if let alternative {
                        // Opens the suggested train as a tap on its own row does.
                        NavigationLink {
                            TrainDetailView(service: alternative.service, query: snapshot.query)
                        } label: {
                            ReliableAlternativeLine(alternative: alternative)
                        }
                        .accessibilityHint(Text(ReliabilityTexts.alternativeOpen(alternative)))
                        .listRowSeparator(.hidden, edges: .top)
                    }
                case let .journey(row):
                    NavigationLink {
                        JourneyDetailView(row: row, reliability: journeys.reliability[row.id])
                    } label: {
                        JourneyResultRow(row: row, reliability: journeys.reliability[row.id])
                    }
                }
            }
            JourneyNoticesView(hasEntries: !merged.entries.isEmpty)

            if let reliability, !reliability.isEmpty {
                Text(ReliabilityTexts.attribution(reliability.windowEnd))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text("\(String(localized: "result.source_label")) \(snapshot.provenance.sourceLabel)")
                .font(.footnote)
                .foregroundStyle(.secondary)
            // Android's advisory of a coverage fallback (route_coverage_fallback): a community timetable, not an official one.
            if snapshot.usesCoverageTimetable {
                Label("result.coverage_fallback", systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            // The timetable's trains without a live value keep the disclosure, as in «Partenze».
            if snapshot.services.contains(where: { model.resultLive[$0.id] == nil }) {
                Label("result.programmed.disclosure", systemImage: "clock")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            LiveSourcesFooter(state: model.resultLiveState, sources: model.resultLiveSources, pauses: model.liveSourcePauses)
        }
        // «Salva orario» and «Condividi» of the direct trains as the list shows them (PersonalViews.swift).
        ResultsActionsSection(snapshot: snapshot, services: shownServices(shown))
    }

    /// The direct trains of the list as shown, in its order (chips, change filter, travel preferences).
    private func shownServices(_ entries: [JourneyResultEntry]) -> [TrainService] {
        entries.compactMap { entry in
            if case let .train(service, _) = entry { return service }
            return nil
        }
    }

    /// The direct trains of this search merged with its journeys with changes (Android's `ResultsMerge`), with
    /// «Ultimo di oggi», «primo domani» and «Nessun treno fino alle …» only for a search of today.
    private var mergedEntries: (entries: [JourneyResultEntry], firstNextDay: Date?, noTrainsUntil: Date?) {
        let markedDay = journeys.markedDay(now: now)
        var rows: [JourneyOptionRow] = []
        if journeys.query == snapshot.query {
            rows = journeys.rows.map { row in
                var copy = row
                if markedDay == nil { copy.lastOfDay = false }
                return copy
            }
        }
        // The changes filter of the planner applies to the journeys with changes as to the direct trains.
        let filtered = rows.filter { journeys.changeFilter.accepts($0.changes) }
        let day = markedDay ?? JourneyResultsMerge.encodedDay(snapshot.query.departure)
        let entries = JourneyResultsMerge.entries(services: snapshot.services, journeys: filtered, serviceDate: day, preferences: journeys.preferences)
        let firstNextDay = markedDay.flatMap { JourneyResultsMerge.firstDepartureNextDay(entries, journeys: rows, serviceDate: $0) }
        let noTrainsUntil = JourneyResultsMerge.noTrainsUntil(entries, journeys: rows, requested: snapshot.query.departure, firstNextDay: firstNextDay)
        return (entries, firstNextDay, noTrainsUntil)
    }

    /// «Più affidabili»: each measured entry's chance of arriving on time (a direct train at the destination, a
    /// journey with every train and change), by the id of its entry.
    private func reliabilityScores(_ trains: [String: StopReliability]) -> [String: EntryReliability] {
        var scores: [String: EntryReliability] = [:]
        for (id, stop) in trains { scores["train-\(id)"] = EntryReliability(train: stop) }
        for (id, journey) in journeys.reliability { scores["journey-\(id)"] = EntryReliability(journey: journey) }
        return scores
    }
}

/// The journeys with changes when no direct train serves the search.
private struct JourneysOnlyResultView: View {
    @EnvironmentObject private var journeys: JourneysModel

    var body: some View {
        if journeys.state != .idle {
            Section("result.journey.title") {
                let filtered = journeys.filteredRows
                let rows = JourneyResultsPresenter.apply(filtered, filter: journeys.filter, preferences: journeys.preferences)
                if !journeys.rows.isEmpty {
                    JourneyFilterChips(
                        selection: journeys.filter,
                        counts: JourneyResultsMerge.counts(filtered.map { JourneyResultEntry.journey($0) }),
                        showsMostReliable: false
                    ) { journeys.filter = $0 }
                }
                JourneyActiveFiltersView(hiddenTrains: 0)
                ForEach(rows) { row in
                    NavigationLink {
                        JourneyDetailView(row: row, reliability: journeys.reliability[row.id])
                    } label: {
                        JourneyResultRow(row: row, reliability: journeys.reliability[row.id])
                    }
                }
                JourneyNoticesView(hasEntries: !rows.isEmpty)
            }
        }
    }
}

/// One listed train: time, number, category (a bus: a bus icon and its label), its live state at the origin when the live board
/// lists it (delay, cancellation, platform and their source, read as one sentence), destination and
/// operator, then bikes on board and the unofficial statistics at the searched destination when known.
private struct ResultRow: View {
    let service: TrainService
    let reliability: StopReliability?
    let live: StationBoardLiveStatus?
    var co2: Co2Estimate? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text(service.scheduledDeparture)
                    .font(.headline.monospacedDigit())
                // A city line has no number: its label says the mode and the line («Metro M1»).
                Text(service.isUrban ? VehicleTexts.rowLabel(service) : service.number)
                    .font(.headline)
                Spacer()
                // A bus shows a bus and its label, never a train's category (as on Android).
                if service.isUrban {
                    Image(systemName: VehicleTexts.symbol(service))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                } else if let busLabel = VehicleTexts.busLabel(service) {
                    Label(busLabel, systemImage: VehicleTexts.symbol(service))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text(service.category)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if let live {
                LiveStatusLine(status: live, showsPlatform: true, scheduledPlatform: service.platform)
            }
            if let boardingStop = VehicleTexts.boardingStop(service) {
                Label(boardingStop, systemImage: "figure.walk")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(service.destination)
            Text(service.operatorName)
                .font(.caption)
                .foregroundStyle(.secondary)
            if let bikesAllowed = service.bikesAllowed {
                BikesLabel(allowed: bikesAllowed)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let reliability {
                let parts = ReliabilityTexts.trainParts(reliability)
                Label {
                    Text(parts.joined(separator: " · "))
                } icon: {
                    Image(systemName: "chart.bar")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .accessibilityLabel(Text(ReliabilityTexts.spoken(parts.joined(separator: ", "))))
            }
            if let co2 {
                Co2Line(estimate: co2)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// «Più puntuale: REG 16000 delle 08:12 · in orario 9 volte su 10» under a train often late at the
/// searched destination: a row of its own that opens the suggested train, read as one sentence
/// that says the statistics are unofficial; what a tap does is its hint.
private struct ReliableAlternativeLine: View {
    let alternative: ResultAlternative

    var body: some View {
        let parts = ReliabilityTexts.alternativeParts(alternative)
        Label {
            Text(parts.joined(separator: " · "))
        } icon: {
            Image(systemName: "arrow.up.arrow.down")
        }
        .font(.caption.weight(.medium))
        .foregroundStyle(.tint)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(ReliabilityTexts.spoken(parts.joined(separator: ", "))))
    }
}

/// «Questa tratta: 83% in orario · …» at the top of the results, with the attribution below the list.
private struct RoutePunctualityView: View {
    let route: RoutePunctuality

    var body: some View {
        let lines = ReliabilityTexts.routeLines(route)
        Label {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(lines.indices, id: \.self) { index in
                    Text(lines[index].joined(separator: " · "))
                }
            }
        } icon: {
            Image(systemName: "chart.bar")
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(ReliabilityTexts.spoken(lines.map { $0.joined(separator: ", ") }.joined(separator: ". "))))
    }
}

private struct UnavailableResultView: View {
    let reason: JourneyUnavailableReason

    var body: some View {
        Section("result.title") {
            ContentUnavailableView(
                titleKey,
                systemImage: reason == .providerInvalid || reason == .noAuthorizedProvider
                    ? "exclamationmark.triangle" : "calendar.badge.exclamationmark",
                description: Text(bodyKey)
            )
            Text("result.unavailable.disclosure")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var titleKey: LocalizedStringKey {
        switch reason {
        case .noJourney: "result.unavailable.title"
        case .scheduleExpired: "result.expired.title"
        case .noAuthorizedProvider, .providerInvalid: "result.provider_error.title"
        }
    }

    private var bodyKey: LocalizedStringKey {
        switch reason {
        case .noJourney: "result.unavailable.body"
        case .scheduleExpired: "result.expired.body"
        case .noAuthorizedProvider, .providerInvalid: "result.provider_error.body"
        }
    }
}
