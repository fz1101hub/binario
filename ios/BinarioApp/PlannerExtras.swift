import SwiftUI
import BinarioKit

// Android's planner modes and round trip on the iPhone: «Treno» (search by number), «Stazione»
// (the board) and «Andata e ritorno».

/// «Treno» and «Stazione», Android's other two planner modes.
struct PlannerModesSection: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Section {
            NavigationLink {
                TrainNumberSearchView()
            } label: {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("planner.mode.train")
                        Text("planner.train.description")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "number")
                }
            }
            Button {
                model.selectedTab = .departures
            } label: {
                Label("planner.mode.station", systemImage: "list.bullet.rectangle")
            }
        }
    }
}

/// «Treno»: today's runs of a number from the bundled timetable and the live sources, independent
/// of any route, in departure order as Android lists its sources' matches; a tap opens the train as
/// a planner row does.
struct TrainNumberSearchView: View {
    @EnvironmentObject private var model: AppModel
    /// A number from a link or a quick action, searched at once.
    var initialNumber: String = ""
    @State private var number = ""
    @State private var searching = false
    @State private var runs: [LiveRunStatus] = []
    @State private var scheduled: [ScheduledNumberRun] = []
    @State private var messageKey: String?
    @State private var partial = false

    /// One match: a run a live source published, or one of the bundled timetable.
    private enum Match: Identifiable {
        case live(LiveRunStatus)
        case scheduled(ScheduledNumberRun)

        var id: String {
            switch self {
            case let .live(run): return "live:" + run.id
            case let .scheduled(run): return "timetable:" + run.id
            }
        }

        var departure: Date {
            switch self {
            case let .live(run): return run.scheduledDeparture
            case let .scheduled(run): return run.departure
            }
        }
    }

    private var matches: [Match] {
        (runs.map(Match.live) + scheduled.map(Match.scheduled)).sorted { $0.departure < $1.departure }
    }

    var body: some View {
        Form {
            Section {
                Text("planner.train.description")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                TextField("planner.train.number", text: $number, prompt: Text("planner.train.number_example"))
                    .keyboardType(.asciiCapable)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .onSubmit(search)
                Button(action: search) {
                    if searching {
                        HStack {
                            ProgressView()
                            Text("planner.train.loading")
                        }
                    } else {
                        Label("planner.search", systemImage: "magnifyingglass")
                    }
                }
                .disabled(searching || query == nil)
            }
            if !matches.isEmpty {
                Section {
                    ForEach(matches) { match in
                        switch match {
                        case let .live(run): TrainNumberRunRow(run: run)
                        case let .scheduled(run): ScheduledNumberRunRow(run: run)
                        }
                    }
                    if partial {
                        Text("planner.train.partial")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(PersonalTexts.format("planner.train.heading", runs.first?.number ?? scheduled.first?.service.number ?? number))
                } footer: {
                    Text("planner.train.subtitle")
                }
            } else if let messageKey {
                Section {
                    Text(LocalizedStringKey(messageKey))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("planner.train.title")
        .onAppear {
            guard number.isEmpty, !initialNumber.isEmpty else { return }
            number = initialNumber
            search()
        }
    }

    /// Android's query: letters and digits, at least one digit, at most 32.
    private var query: String? {
        let cleaned = number.uppercased().filter { $0.isLetter || $0.isNumber }
        guard (1...32).contains(cleaned.count), cleaned.contains(where: \.isNumber) else { return nil }
        return cleaned
    }

    private func search() {
        guard let query, !searching else { return }
        searching = true
        messageKey = nil
        Task {
            // The bundled timetable first, as Android shows its offline rows before the live ones.
            scheduled = await model.scheduledNumberRuns(query, on: PersonalModel.today())
            runs = []
            partial = false
            let result = await model.trainNumberRuns(LiveTrainStatusRequest(number: query, serviceDate: Date()))
            searching = false
            var failed = false
            switch result {
            case nil:
                // «Dati in tempo reale» off: the bundled timetable alone.
                break
            case let .unavailable(reason)?:
                failed = !(reason == .notFound || reason == .notCovered)
            case let .available(lookup)?:
                runs = lookup.runs
                partial = !lookup.unavailableSources.isEmpty
            }
            if matches.isEmpty {
                messageKey = failed ? "planner.train.unavailable" : "planner.train.not_found"
            } else {
                messageKey = nil
                partial = partial || failed
            }
            DeparturesTexts.announce(matches.isEmpty ? NSLocalizedString(messageKey ?? "planner.train.not_found", comment: "")
                                                     : PersonalTexts.format("planner.train.heading", query))
        }
    }
}

/// One run of the bundled timetable: route, departure date and time, «Orari programmati»; opens
/// the train's detail from its first stop.
private struct ScheduledNumberRunRow: View {
    let run: ScheduledNumberRun

    var body: some View {
        let label = VStack(alignment: .leading, spacing: 3) {
            Text(verbatim: "\(run.service.category) \(run.service.number) · \(run.service.operatorName)")
                .font(.headline)
            Text(verbatim: "\(run.origin.name) → \(run.destination.name)")
            Text(PersonalTexts.format("planner.train.run_date", run.departure.formatted(
                Date.FormatStyle(date: .abbreviated, time: .shortened, timeZone: ItalianWallClock.timeZone))))
                .font(.caption)
                .foregroundStyle(.secondary)
            Label("result.programmed.disclosure", systemImage: "clock")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        if let query = try? JourneyQuery(origin: run.origin, destination: run.destination, departure: run.departure) {
            NavigationLink {
                TrainDetailView(service: run.service, query: query)
            } label: { label }
            .accessibilityHint(Text("accessibility.open_train_detail"))
        } else {
            label
        }
    }
}

extension AppModel {
    /// «Treno» in the bundled timetable and the installed foreign ones, off the main actor.
    func scheduledNumberRuns(_ number: String, on date: PlannedLocalDate) async -> [ScheduledNumberRun] {
        guard let timetable = repository as? SQLiteGTFSJourneyRepository else { return [] }
        return await timetable.scheduledRuns(numbered: number, on: date)
    }
}

/// One run of the number: route, departure date and time, state; opens the train's detail.
private struct TrainNumberRunRow: View {
    @EnvironmentObject private var model: AppModel
    let run: LiveRunStatus

    var body: some View {
        let label = VStack(alignment: .leading, spacing: 3) {
            Text(verbatim: "\(run.category) \(run.number) · \(run.operatorName)")
                .font(.headline)
            Text(verbatim: "\(run.originName) → \(run.destinationName)")
            Text(PersonalTexts.format("planner.train.run_date", run.scheduledDeparture.formatted(
                Date.FormatStyle(date: .abbreviated, time: .shortened, timeZone: ItalianWallClock.timeZone))))
                .font(.caption)
                .foregroundStyle(.secondary)
            if run.cancellation != LiveCancellation.none || run.state == .cancelled {
                Text(LiveTexts.cancelled)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BinarioPalette.statusError)
            } else if run.operationalStatusVerified, let delay = run.delayMinutes {
                Text(delay > 0 ? LiveTexts.delaySentence(delay) : LiveTexts.onTime)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(delay > 0 ? BinarioPalette.statusError : Color.primary)
            }
        }
        .accessibilityElement(children: .combine)
        if let pair = detail {
            NavigationLink {
                TrainDetailView(service: pair.service, query: pair.query)
            } label: { label }
            .accessibilityHint(Text("accessibility.open_train_detail"))
        } else {
            label
        }
    }

    /// The run as a planner row: boarded at its origin, measured at its destination.
    private var detail: (service: TrainService, query: JourneyQuery)? {
        func identity(_ name: String, code: String?) -> StationIdentity {
            if let station = model.catalogStation(named: name) { return StationIdentity(station) }
            return StationIdentity(id: "live:" + (code ?? name), name: name)
        }
        let origin = identity(run.originName, code: run.originCode)
        let destination = identity(run.destinationName, code: nil)
        guard let query = try? JourneyQuery(origin: origin, destination: destination, departure: run.scheduledDeparture) else { return nil }
        let service = TrainService(
            id: "live:\(run.id)",
            number: run.number,
            operatorName: run.operatorName,
            category: run.category,
            destination: run.destinationName,
            scheduledDeparture: LiveTexts.clock(run.scheduledDeparture),
            expectedDeparture: LiveTexts.clock(run.expectedDeparture),
            status: "",
            platform: run.platform,
            delayMinutes: run.delayMinutes ?? 0
        )
        return (service: service, query: query)
    }
}

/// «Andata e ritorno» in the planner's form: the switch and the return's date and time.
struct RoundTripFields: View {
    @Binding var roundTrip: Bool
    @Binding var returnDeparture: Date

    var body: some View {
        Toggle("planner.round_trip", isOn: $roundTrip)
        if roundTrip {
            DatePicker("planner.return", selection: $returnDeparture)
                .environment(\.timeZone, AppModel.timetableTimeZone)
        }
    }
}

/// «RITORNO»: the trains of the way back, as the outbound rows, each opening its detail.
struct ReturnResultsSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var journeys: JourneysModel
    let result: JourneyResult

    var body: some View {
        Section {
            switch result {
            case let .available(snapshot):
                let co2 = model.co2Estimate(from: snapshot.query.origin, to: snapshot.query.destination)
                let now = Date()
                // The return leg's live values as Android's return list: its origin's board, its live route
                // (LiveRunMerge) and the runs the timetable lacks.
                let overlay = LiveRouteOverlay(
                    planned: snapshot.services, board: journeys.returnBoardLive(snapshot, now: now),
                    runs: journeys.routeRuns(for: snapshot.query, now: now), query: snapshot.query, now: now
                )
                let services = (snapshot.services + overlay.added).enumerated().sorted { first, second in
                    let a = first.element.departureDate ?? .distantFuture, b = second.element.departureDate ?? .distantFuture
                    return a != b ? a < b : first.offset < second.offset
                }.map(\.element)
                ForEach(services, id: \.id) { service in
                    let live = overlay.live[service.id]
                    NavigationLink {
                        TrainDetailView(service: service, query: snapshot.query, boardLive: live)
                    } label: {
                        ResultRow(service: service, reliability: nil, live: live, co2: co2, expectedArrival: overlay.arrivals[service.id])
                    }
                    .accessibilityHint(Text("accessibility.open_train_detail"))
                }
                if snapshot.services.contains(where: { overlay.live[$0.id] == nil }) {
                    Label("result.programmed.disclosure", systemImage: "clock")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                LiveRouteSourceNotes(sources: journeys.routeSources(for: snapshot.query, now: now))
            case .unavailable:
                Text("planner.return.empty_title")
                    .font(.body.weight(.semibold))
                Text("planner.return.empty_body")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("result.share.return_title")
        }
    }
}
