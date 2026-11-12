import SwiftUI
import BinarioKit

/// «Agenda» (in «Tratte»): the journeys planned on this iPhone only, «Prossimi» (the first to
/// leave first) or «Archiviati» (completed or departed, the latest first), on the Italian clock.
/// Dates and times are the stored Italian ones, never converted to the phone's zone. A journey
/// opens its page (checklist, «Modifica», «Completa», «Elimina»); «+» plans a new one.
struct AgendaView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var filter: AgendaFilter = .upcoming
    @State private var editor: AgendaEditorRequest?
    /// The minute the lists are read at: a journey that departs moves to «Archiviati».
    @State private var clock = Date()

    enum AgendaFilter: Hashable {
        case upcoming
        case archive
    }

    private var journeys: [PlannedJourney] {
        switch filter {
        case .upcoming: PlannedJourneyAgenda.upcoming(model.agenda.journeys, now: clock)
        case .archive: PlannedJourneyAgenda.archive(model.agenda.journeys, now: clock)
        }
    }

    var body: some View {
        List {
            if model.agenda.isReadOnly {
                Section {
                    Label("saved.agenda.unreadable", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(BinarioPalette.statusError)
                }
            }

            Section {
                Picker("saved.agenda.title", selection: $filter) {
                    Text("saved.agenda.upcoming").tag(AgendaFilter.upcoming)
                    Text("saved.agenda.archive").tag(AgendaFilter.archive)
                }
                .pickerStyle(.segmented)
            }

            Section {
                if journeys.isEmpty {
                    Text("saved.agenda.empty")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(journeys) { journey in
                        NavigationLink {
                            AgendaJourneyView(journeyID: journey.id)
                        } label: {
                            AgendaJourneyRow(journey: journey)
                        }
                    }
                }
            } footer: {
                Text("saved.agenda.local_only")
            }
        }
        .navigationTitle("saved.agenda.title")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editor = .new
                } label: {
                    Label("saved.agenda.add", systemImage: "plus")
                }
                .disabled(model.agenda.isReadOnly)
            }
        }
        .sheet(item: $editor) { request in
            AgendaEditorView(request: request)
        }
        .onAppear {
            model.reloadAgenda()
            clock = Date()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.reloadAgenda()
                clock = Date()
            }
        }
        .task { await followTheClock() }
    }

    /// Every minute while the Agenda is open; nothing is asked anywhere.
    private func followTheClock() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
            clock = Date()
        }
    }
}

/// One journey of the list: the route, the stored Italian date and time, the label.
struct AgendaJourneyRow: View {
    let journey: PlannedJourney

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: AgendaText.route(journey))
                .font(.headline)
                .foregroundStyle(.primary)
            Text(verbatim: AgendaText.departure(journey))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if !journey.label.isEmpty {
                Text(verbatim: journey.label)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The page of one journey: its values, its checklist to tick and its actions.
struct AgendaJourneyView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let journeyID: String
    @State private var editor: AgendaEditorRequest?
    @State private var confirmingDelete = false
    @State private var messageKey: String?

    private var journey: PlannedJourney? {
        model.agenda.journeys.first { $0.id == journeyID }
    }

    var body: some View {
        Group {
            if let journey {
                content(journey)
            } else {
                ContentUnavailableView("saved.agenda.empty", systemImage: "calendar")
            }
        }
        .sheet(item: $editor) { request in
            AgendaEditorView(request: request)
        }
        .alert(
            LocalizedStringKey(messageKey ?? "saved.agenda.storage_error"),
            isPresented: Binding(
                get: { messageKey != nil },
                set: { if !$0 { messageKey = nil } }
            )
        ) {
            Button("action.ok", role: .cancel) { messageKey = nil }
        }
    }

    private func content(_ journey: PlannedJourney) -> some View {
        let readOnly = model.agenda.isReadOnly
        return List {
            Section {
                LabeledContent("planner.origin", value: journey.origin.displayName)
                LabeledContent("planner.destination", value: journey.destination.displayName)
                LabeledContent("planner.departure", value: AgendaText.departure(journey))
                if !journey.label.isEmpty {
                    LabeledContent("saved.agenda.label", value: journey.label)
                }
            } footer: {
                if AppModel.needsTimetableZoneCaption(at: journey.departure.instant) {
                    Text("planner.departure.italian_time")
                }
            }

            if !journey.checklist.isEmpty {
                Section("saved.agenda.checklist") {
                    ForEach(journey.checklist) { item in
                        Toggle(isOn: Binding(
                            get: { item.completed },
                            set: { report(model.setChecklistItem(item.id, completed: $0, journeyID: journey.id)) }
                        )) {
                            Text(verbatim: item.label)
                        }
                    }
                }
                .disabled(readOnly)
            }

            Section {
                if journey.status == .active {
                    Button {
                        editor = .edit(journey)
                    } label: {
                        Label("saved.agenda.edit", systemImage: "pencil")
                    }
                    Button {
                        report(model.completeJourney(id: journey.id))
                    } label: {
                        Label("saved.agenda.complete", systemImage: "checkmark.circle")
                    }
                }
                Button(role: .destructive) {
                    confirmingDelete = true
                } label: {
                    Label("saved.agenda.delete", systemImage: "trash")
                }
                .confirmationDialog("saved.agenda.delete_confirmation", isPresented: $confirmingDelete, titleVisibility: .visible) {
                    Button("saved.agenda.delete", role: .destructive) {
                        if let refusal = model.deleteJourney(id: journey.id) {
                            report(refusal)
                        } else {
                            dismiss()
                        }
                    }
                }
            }
            .disabled(readOnly)
        }
        .navigationTitle(Text(verbatim: AgendaText.route(journey)))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func report(_ refusal: PlannedJourneyRefusal?) {
        guard let refusal else { return }
        messageKey = AgendaText.messageKey(refusal)
    }
}

/// What opens the editor.
enum AgendaEditorRequest: Identifiable {
    case new
    case edit(PlannedJourney)

    var id: String {
        switch self {
        case .new: "new"
        case let .edit(journey): "edit:\(journey.id)"
        }
    }
}

/// The editor of a journey, in a sheet: the planner's station pickers, the date and time in
/// Italian time, an optional label and the checklist typed one line per item.
struct AgendaEditorView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let request: AgendaEditorRequest

    @State private var label = ""
    @State private var origin: Station?
    @State private var destination: Station?
    /// Nil until chosen.
    @State private var departure: Date?
    @State private var checklistText = ""
    @State private var messageKey: String?
    @State private var loaded = false

    private var existing: PlannedJourney? {
        if case let .edit(journey) = request { return journey }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    StationPickerField(titleKey: "planner.origin", selection: $origin)
                    StationPickerField(titleKey: "planner.destination", selection: $destination)
                    if let departure {
                        // Stored and shown in Italian time: pick it in the same zone.
                        DatePicker("planner.departure", selection: Binding(
                            get: { departure },
                            set: { self.departure = $0 }
                        ))
                        .environment(\.timeZone, ItalianWallClock.timeZone)
                        if AppModel.needsTimetableZoneCaption(at: departure) {
                            Text("planner.departure.italian_time")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section {
                    TextField("saved.agenda.label", text: $label)
                }

                Section {
                    TextField("saved.agenda.checklist", text: $checklistText, axis: .vertical)
                        .lineLimit(3...8)
                } header: {
                    Text("saved.agenda.checklist")
                } footer: {
                    Text("saved.agenda.checklist_hint")
                }

                if let messageKey {
                    Section {
                        Text(LocalizedStringKey(messageKey))
                            .foregroundStyle(BinarioPalette.statusError)
                    }
                }
            }
            .navigationTitle(LocalizedStringKey(existing == nil ? "saved.agenda.add" : "saved.agenda.edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("saved.agenda.cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("saved.agenda.save", action: save)
                }
            }
            .onAppear(perform: load)
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        let form: PlannedJourneyForm
        switch request {
        case .new: form = PlannedJourneyForm.new(now: Date())
        case let .edit(journey): form = PlannedJourneyForm(editing: journey)
        }
        label = form.label
        origin = form.origin.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        destination = form.destination.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        departure = form.departureInstant
        checklistText = form.checklistText
    }

    private func save() {
        var form = PlannedJourneyForm(
            label: label,
            origin: origin.map { StationIdentity($0) },
            destination: destination.map { StationIdentity($0) },
            checklistText: checklistText
        )
        if let departure {
            form.date = ItalianWallClock.date(of: departure)
            form.time = ItalianWallClock.time(of: departure)
        }
        switch form.journey(id: UUID().uuidString, now: Date(), editing: existing) {
        case let .failure(issue):
            messageKey = AgendaText.messageKey(issue)
        case let .success(journey):
            if let refusal = model.saveJourney(journey, isNew: existing == nil) {
                messageKey = AgendaText.messageKey(refusal)
            } else {
                dismiss()
            }
        }
    }
}

/// The Agenda's texts that depend on values.
enum AgendaText {
    /// «Roma Termini → Milano Centrale».
    static func route(_ journey: PlannedJourney) -> String {
        "\(journey.origin.displayName) → \(journey.destination.displayName)"
    }

    /// «gio 12 nov 2026 · 08:15»: the stored Italian date in the user's language and the stored
    /// Italian time as it was saved, never a conversion to the phone's zone.
    static func departure(_ journey: PlannedJourney) -> String {
        "\(date(journey.departure.date)) · \(journey.departure.time.hhmm)"
    }

    static func date(_ date: PlannedLocalDate) -> String {
        // Midday of that Italian day, written in the Italian zone, is that day in every calendar.
        guard let midday = PlannedLocalTime(hour: 12, minute: 0),
              let instant = ItalianWallClock.instant(date: date, time: midday)
        else { return date.iso }
        var style = Date.FormatStyle.dateTime.weekday(.abbreviated).day().month(.abbreviated).year()
        style.timeZone = ItalianWallClock.timeZone
        return instant.formatted(style)
    }

    static func messageKey(_ refusal: PlannedJourneyRefusal) -> String {
        switch refusal {
        case .readOnly: "saved.agenda.read_only"
        case .full: "saved.agenda.full"
        case .duplicate: "saved.agenda.duplicate"
        case .notFound, .storageFailure: "saved.agenda.storage_error"
        case let .invalid(error): error == .sameStation ? "planner.same_station" : "saved.agenda.invalid"
        }
    }

    static func messageKey(_ issue: PlannedJourneyFormIssue) -> String {
        switch issue {
        case .missingStations: "planner.selection_required"
        case .sameStation: "planner.same_station"
        case .invalidDeparture, .invalid: "saved.agenda.invalid"
        case .labelTooLong: "saved.agenda.label_too_long"
        case .checklistLineTooLong: "saved.agenda.checklist_too_long"
        case .sensitiveChecklist: "saved.agenda.checklist_sensitive"
        }
    }
}
