import SwiftUI
import BinarioKit
#if canImport(UIKit)
import UIKit
#endif

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
    @State private var importing = false
    @State private var importingText = false
    /// A picked file, read and compared with the Agenda; nothing is written before a choice.
    @State private var filePreview: PlannedJourneyFilePreview?
    /// What the last export or import did, shown at the top of the list.
    @State private var fileMessage: (key: String, isError: Bool)?
    @State private var confirmingClear = false
    /// The search and month filter (Android's): on this iPhone, never sent.
    @State private var query = ""
    @State private var month: PlannedJourneyMonth?

    enum AgendaFilter: Hashable {
        case upcoming
        case archive
    }

    /// The journeys of the list chosen, before the search.
    private var listed: [PlannedJourney] {
        switch filter {
        case .upcoming: PlannedJourneyAgenda.upcoming(model.agenda.journeys, now: clock)
        case .archive: PlannedJourneyAgenda.archive(model.agenda.journeys, now: clock)
        }
    }

    private var months: [PlannedJourneyMonth] {
        PlannedJourneyAgendaSearch.months(listed, now: clock)
    }

    private var journeys: [PlannedJourney] {
        // A month no longer in the list shows every month.
        let shownMonth = month.flatMap { months.contains($0) ? $0 : nil }
        return PlannedJourneyAgendaSearch.filter(listed, now: clock, query: query, month: shownMonth)
    }

    var body: some View {
        List {
            if model.agenda.isReadOnly {
                Section {
                    Label("saved.agenda.unreadable", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(BinarioPalette.statusError)
                }
            }

            if let fileMessage {
                Section {
                    Label(LocalizedStringKey(fileMessage.key), systemImage: fileMessage.isError ? "exclamationmark.triangle" : "checkmark.circle")
                        .foregroundStyle(fileMessage.isError ? BinarioPalette.statusError : Color.primary)
                }
            }

            // «Pronto per partire»: the next journey, ticking with the Agenda's clock.
            if let next = PlannedJourneyAgenda.next(model.agenda.journeys, now: clock) {
                Section {
                    NavigationLink {
                        AgendaJourneyView(journeyID: next.id)
                    } label: {
                        AgendaReadyCard(journey: next, now: clock)
                    }
                } header: {
                    Text("saved.agenda.ready_title")
                }
            }

            Section {
                Picker("saved.agenda.title", selection: $filter) {
                    Text("saved.agenda.upcoming").tag(AgendaFilter.upcoming)
                    Text("saved.agenda.archive").tag(AgendaFilter.archive)
                }
                .pickerStyle(.segmented)
                if months.count > 1 {
                    Picker("saved.agenda.month", selection: $month) {
                        Text("saved.agenda.all_months").tag(PlannedJourneyMonth?.none)
                        ForEach(months, id: \.self) { choice in
                            Text(verbatim: AgendaText.month(choice)).tag(PlannedJourneyMonth?.some(choice))
                        }
                    }
                    .pickerStyle(.menu)
                }
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
                            AgendaJourneyRow(journey: journey, now: clock)
                        }
                    }
                }
            } footer: {
                Text("saved.agenda.local_only")
            }

            // The Agenda's own settings, on this iPhone only.
            AgendaSettingsSection(confirmingClear: $confirmingClear)
        }
        .navigationTitle("saved.agenda.title")
        .searchable(text: $query, prompt: Text("saved.agenda.search"))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editor = .new
                } label: {
                    Label("saved.agenda.add", systemImage: "plus")
                }
                .disabled(model.agenda.isReadOnly)
            }
            // Android's formats: the JSON document restores editable journeys, the calendar is a
            // copy for other apps. Readable journeys can be exported also from a read-only Agenda.
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    ShareLink(item: AgendaJSONFile(journeys: model.agenda.journeys), preview: SharePreview("saved.agenda.export_json")) {
                        Label("saved.agenda.export_json", systemImage: "square.and.arrow.up")
                    }
                    .disabled(model.agenda.journeys.isEmpty)
                    ShareLink(item: AgendaCalendarFile(journeys: model.agenda.journeys), preview: SharePreview("saved.agenda.export_ics")) {
                        Label("saved.agenda.export_ics", systemImage: "calendar")
                    }
                    .disabled(model.agenda.journeys.isEmpty)
                    Button {
                        fileMessage = nil
                        importing = true
                    } label: {
                        Label("saved.agenda.import", systemImage: "square.and.arrow.down")
                    }
                    .disabled(model.agenda.isReadOnly)
                    Button {
                        fileMessage = nil
                        importingText = true
                    } label: {
                        Label("saved.agenda.text_import", systemImage: "text.badge.plus")
                    }
                    .disabled(model.agenda.isReadOnly)
                } label: {
                    Label("saved.agenda.files", systemImage: "arrow.up.arrow.down.circle")
                }
            }
        }
        .sheet(item: $editor) { request in
            AgendaEditorView(request: request)
        }
        // «Svuota l'Agenda»: reminders first, then every journey (readable or not) and template.
        .confirmationDialog("saved.agenda.clear_title", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("saved.agenda.clear_confirm", role: .destructive) {
                model.clearAgenda()
                report("saved.agenda.cleared", isError: false)
            }
        } message: {
            Text("saved.agenda.clear_body")
        }
        .sheet(isPresented: $importingText) {
            AgendaTextImportView()
        }
        // Cancelling the picker is not an error: nothing is called then.
        .fileImporter(isPresented: $importing, allowedContentTypes: AgendaFiles.importTypes) { result in
            guard case let .success(url) = result, let data = AgendaFiles.read(url),
                  let preview = model.previewAgendaFile(data)
            else {
                report("saved.agenda.import_invalid", isError: true)
                return
            }
            filePreview = preview
        }
        .alert(
            Text(LocalizedStringKey(AgendaText.filePreviewTitleKey(filePreview))),
            isPresented: Binding(
                get: { filePreview != nil },
                set: { if !$0 { filePreview = nil } }
            ),
            presenting: filePreview
        ) { preview in
            if case let .journeys(journeys) = preview, journeys.hasChanges {
                if journeys.conflicts.isEmpty {
                    Button("saved.agenda.import") { apply(journeys, .keepExisting) }
                } else {
                    Button("saved.agenda.import_keep") { apply(journeys, .keepExisting) }
                    Button("saved.agenda.import_replace", role: .destructive) { apply(journeys, .replaceExisting) }
                }
                Button("saved.agenda.cancel", role: .cancel) {}
            } else {
                Button("action.ok", role: .cancel) {}
            }
        } message: { preview in
            Text(verbatim: AgendaText.filePreviewMessage(preview))
        }
        .onAppear {
            model.reloadAgenda()
            clock = Date()
            Task { await model.refreshReminders() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.reloadAgenda()
                clock = Date()
            }
        }
        .task { await followTheClock() }
    }

    private func apply(_ preview: PlannedJourneyImportPreview, _ policy: PlannedJourneyConflictPolicy) {
        if let refusal = model.importJourneys(preview, policy: policy) {
            report(AgendaText.importMessageKey(refusal), isError: true)
        } else {
            report("saved.agenda.import_done", isError: false)
        }
    }

    /// Shown at the top of the list and said by VoiceOver.
    private func report(_ key: String, isError: Bool) {
        fileMessage = (key, isError)
        AgendaAnnouncement.post(key, isError ? .error : .information)
    }

    /// Every minute while the Agenda is open; nothing is asked anywhere.
    private func followTheClock() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
            clock = Date()
        }
    }
}

/// «Pronto per partire» (Android's card): the next journey, its stored date and time, a countdown
/// that follows the Agenda's clock, the reminder the system really holds, the checklist left and a
/// reminder that this is the saved plan only.
struct AgendaReadyCard: View {
    @EnvironmentObject private var model: AppModel
    let journey: PlannedJourney
    let now: Date

    var body: some View {
        let occurrence = journey.shownOccurrence(now: now)
        let left = journey.checklist.filter { !$0.completed }.count
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: journey.label.isEmpty ? AgendaText.route(journey) : journey.label)
                .font(.headline)
            if !journey.label.isEmpty {
                Text(verbatim: AgendaText.route(journey))
                    .font(.subheadline)
            }
            Text(verbatim: AgendaText.departure(occurrence.departure))
                .font(.subheadline)
            Text(verbatim: AgendaText.countdown(PlannedJourneyCountdown.between(now: now, departure: occurrence.instant)))
                .font(.subheadline.weight(.semibold))
            Text(verbatim: AgendaText.readyReminder(model.reminderStatus.state(of: journey, now: now)))
                .font(.footnote)
            if !journey.checklist.isEmpty {
                Text(verbatim: AgendaText.checklistLeft(left))
                    .font(.footnote)
            }
            Text("saved.agenda.ready_local_note")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The Agenda's settings: the lead proposed for new journeys (default «Nessuno», as Android) and
/// «Svuota l'Agenda», also offered when the stored Agenda is read-only (it is the way out).
struct AgendaSettingsSection: View {
    @EnvironmentObject private var model: AppModel
    @Binding var confirmingClear: Bool

    var body: some View {
        Section {
            Picker("saved.agenda.default_reminder", selection: Binding(
                get: { model.agendaDefaultReminder },
                set: { minutes in model.setAgendaDefaultReminder(minutes) }
            )) {
                ForEach(PlannedJourneyForm.reminderChoices, id: \.self) { choice in
                    Text(verbatim: AgendaText.reminderChoice(choice)).tag(choice)
                }
            }
            Button(role: .destructive) {
                confirmingClear = true
            } label: {
                Label("saved.agenda.clear", systemImage: "trash")
            }
            .disabled(model.agenda.journeys.isEmpty && !model.agenda.isReadOnly && model.agendaTemplates.isEmpty)
        } footer: {
            Text("saved.agenda.default_reminder_hint")
        }
    }
}

/// One journey of the list: the first origin and the last destination, the stored date and time of
/// its next departure (the last one once none is left), how many legs and how it repeats, the label.
struct AgendaJourneyRow: View {
    let journey: PlannedJourney
    var now = Date()

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: AgendaText.route(journey))
                .font(.headline)
                .foregroundStyle(.primary)
            Text(verbatim: AgendaText.departure(journey.shownOccurrence(now: now).departure))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if journey.segments.count > 1 {
                Text(verbatim: AgendaText.legCount(journey.segments.count))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if let recurrence = journey.recurrence {
                Label {
                    Text(verbatim: AgendaText.repetition(recurrence))
                } icon: {
                    Image(systemName: "repeat")
                        .accessibilityHidden(true)
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
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
    @Environment(\.openURL) private var openURL
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
        // A repeating journey shows its next departure (the last one once none is left).
        let shown = journey.shownOccurrence(now: Date())
        return List {
            Section {
                LabeledContent("planner.origin", value: journey.origin.displayName)
                LabeledContent("planner.destination", value: journey.destination.displayName)
                LabeledContent("planner.departure", value: AgendaText.departure(shown.departure))
                if let recurrence = journey.recurrence {
                    LabeledContent("saved.agenda.repeat", value: AgendaText.repetition(recurrence))
                }
                if !journey.label.isEmpty {
                    LabeledContent("saved.agenda.label", value: journey.label)
                }
                LabeledContent("saved.agenda.reminder", value: AgendaText.reminderChoice(journey.reminderMinutes))
            } footer: {
                if AppModel.needsTimetableZoneCaption(at: shown.instant) {
                    Text("planner.departure.italian_time")
                }
            }

            // Every leg of a journey of several, on the date shown above.
            if shown.segments.count > 1 {
                ForEach(Array(shown.segments.enumerated()), id: \.offset) { index, segment in
                    Section {
                        LabeledContent("planner.origin", value: segment.origin.displayName)
                        LabeledContent("planner.destination", value: segment.destination.displayName)
                        LabeledContent("planner.departure", value: AgendaText.departure(segment.departure))
                    } header: {
                        Text(verbatim: AgendaText.legTitle(index, of: shown.segments.count))
                    }
                }
            }

            // What the system really holds, not only the lead chosen.
            let reminder = model.reminderStatus.state(of: journey, now: Date())
            if let key = AgendaText.reminderStateKey(reminder) {
                Section {
                    Label(LocalizedStringKey(key), systemImage: AgendaText.reminderSymbol(reminder))
                    if case .notAllowed = reminder, let url = AgendaText.notificationSettingsURL {
                        Button("saved.agenda.reminder.settings") { openURL(url) }
                    }
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

            // One event in Android's calendar format, through the system's share sheet.
            Section {
                ShareLink(item: AgendaCalendarFile(journeys: [journey]), preview: SharePreview("saved.agenda.export_calendar")) {
                    Label("saved.agenda.export_calendar", systemImage: "calendar.badge.plus")
                }
            }

            // «Ritorno» (legs reversed, stations swapped) and «Ripianifica» (the same legs) open the
            // editor on a new journey: every date and time is chosen anew.
            Section {
                Button {
                    editor = .returnTrip(journey)
                } label: {
                    Label("saved.agenda.return", systemImage: "arrow.uturn.backward")
                }
                Button {
                    editor = .replan(journey)
                } label: {
                    Label("saved.agenda.replan", systemImage: "arrow.clockwise")
                }
            }
            .disabled(readOnly)

            Section {
                if journey.status == .active {
                    Button {
                        editor = .edit(journey)
                    } label: {
                        Label("saved.agenda.edit", systemImage: "pencil")
                    }
                    // On a repeating journey «Completa» marks its next departure done (Android's rule).
                    Button {
                        if let refusal = model.completeJourney(id: journey.id) {
                            report(refusal)
                        } else {
                            AgendaAnnouncement.post("saved.agenda.completed", .information)
                        }
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
                            AgendaAnnouncement.post("saved.agenda.deleted", .information)
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
    /// «Ritorno»: the stations swapped, on a date and time chosen anew.
    case returnTrip(PlannedJourney)
    /// «Ripianifica»: the same stations, on a date and time chosen anew.
    case replan(PlannedJourney)

    var id: String {
        switch self {
        case .new: "new"
        case let .edit(journey): "edit:\(journey.id)"
        case let .returnTrip(journey): "return:\(journey.id)"
        case let .replan(journey): "replan:\(journey.id)"
        }
    }

    /// What the editor says first, for a journey prepared from another.
    var noticeKey: String? {
        switch self {
        case .new, .edit: nil
        case .returnTrip: "saved.agenda.return_prepared"
        case .replan: "saved.agenda.replan_prepared"
        }
    }
}

/// The editor of a journey, in a sheet: the planner's station pickers, the date and time in
/// Italian time, an optional label and the checklist typed one line per item.
struct AgendaEditorView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let request: AgendaEditorRequest

    @State private var label = ""
    @State private var origin: Station?
    @State private var destination: Station?
    /// Nil until chosen.
    @State private var departure: Date?
    @State private var checklistText = ""
    @State private var reminderMinutes: Int?
    /// The first leg's stored identifier while a journey is edited.
    @State private var firstSegmentID: String?
    /// Legs 2 to 4.
    @State private var laterLegs: [AgendaEditorLeg] = []
    /// Journeys in all; 1 departs once.
    @State private var repeatCount = 1
    @State private var repeatWeekdays: Set<PlannedJourneyWeekday> = []
    @State private var repeatEndsOn: PlannedLocalDate?
    @State private var templateName = ""
    /// What the last template action did, under the templates.
    @State private var templateMessage: String?
    @State private var messageKey: String?
    @State private var loaded = false
    /// The journey is saved and its reminder is being scheduled: «Salva» waits.
    @State private var saving = false
    /// Saved, but its reminder could not be scheduled: said before the editor closes.
    @State private var savedNoticeKey: String?
    /// The past-departure warning was shown: a second «Salva» saves (Android's rule).
    @State private var pastDepartureConfirmed = false

    private var existing: PlannedJourney? {
        if case let .edit(journey) = request { return journey }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                if let noticeKey = request.noticeKey {
                    Section {
                        Label(LocalizedStringKey(noticeKey), systemImage: "info.circle")
                    }
                }

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
                    } else {
                        // A return or a replanned journey: the date and time are chosen anew.
                        Button {
                            departure = PlannedJourneyForm.new(now: Date()).departureInstant
                        } label: {
                            LabeledContent("planner.departure") {
                                Text("saved.agenda.unset")
                            }
                        }
                    }
                }

                // Legs 2 to 4 (Android's multi-leg editor).
                AgendaEditorLaterLegs(legs: $laterLegs, firstDestination: destination, firstDeparture: departure)

                // Repeated on chosen weekdays (Android's recurrence editor).
                AgendaEditorRepetition(
                    count: $repeatCount,
                    weekdays: $repeatWeekdays,
                    startDay: departure.flatMap { ItalianWallClock.date(of: $0) }.map { PlannedJourneyWeekday(of: $0) }
                )

                // Journey templates (Android's), one leg each.
                AgendaEditorTemplates(
                    templates: model.agendaTemplates,
                    canSave: laterLegs.isEmpty,
                    name: $templateName,
                    message: templateMessage,
                    apply: { applyTemplate($0) },
                    save: { saveTemplate() },
                    delete: { deleteTemplate($0) }
                )

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

                // A local notification at the Italian departure minus the lead; the permission is
                // asked only when a journey with a reminder is saved.
                Section {
                    Picker("saved.agenda.reminder", selection: $reminderMinutes) {
                        ForEach(PlannedJourneyForm.reminderChoices, id: \.self) { choice in
                            Text(verbatim: AgendaText.reminderChoice(choice)).tag(choice)
                        }
                    }
                } footer: {
                    Text("saved.agenda.reminder.hint")
                }

                // «Preparazione del viaggio»: ready-made lines added after the user's own.
                Section("saved.agenda.preparation") {
                    ForEach(PlannedJourneyChecklistPreset.allCases, id: \.self) { preset in
                        Button {
                            checklistText = preset.applying(to: checklistText) { AgendaText.checklistLine($0) }
                        } label: {
                            Label(LocalizedStringKey(AgendaText.presetKey(preset)), systemImage: AgendaText.presetSymbol(preset))
                        }
                        .accessibilityHint(Text("saved.agenda.preparation_hint"))
                    }
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
                        .disabled(saving)
                }
            }
            .onAppear(perform: load)
            .onChange(of: departure) { _, _ in pastDepartureConfirmed = false }
            // The second leg starts where the first arrives while it still showed that station.
            .onChange(of: destination) { previous, current in
                if !laterLegs.isEmpty, laterLegs[0].origin == previous {
                    laterLegs[0].origin = current
                }
            }
            .alert(
                LocalizedStringKey(savedNoticeKey ?? "saved.agenda.saved"),
                isPresented: Binding(
                    get: { savedNoticeKey != nil },
                    set: { if !$0 { savedNoticeKey = nil } }
                )
            ) {
                if savedNoticeKey == "saved.agenda.saved_reminder_denied", let url = AgendaText.notificationSettingsURL {
                    Button("saved.agenda.reminder.settings") {
                        savedNoticeKey = nil
                        openURL(url)
                        dismiss()
                    }
                }
                Button("action.ok", role: .cancel) {
                    savedNoticeKey = nil
                    dismiss()
                }
            }
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        let form: PlannedJourneyForm
        switch request {
        // A new journey, a return and a replanned one propose the default lead (Android's rule).
        case .new: form = PlannedJourneyForm.new(now: Date(), reminderMinutes: model.agendaDefaultReminder)
        case let .edit(journey): form = PlannedJourneyForm(editing: journey)
        case let .returnTrip(journey): form = PlannedJourneyForm.returnTrip(from: journey, reminderMinutes: model.agendaDefaultReminder)
        case let .replan(journey): form = PlannedJourneyForm.replan(from: journey, reminderMinutes: model.agendaDefaultReminder)
        }
        label = form.label
        origin = form.origin.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        destination = form.destination.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        departure = form.departureInstant
        checklistText = form.checklistText
        reminderMinutes = form.reminderMinutes
        firstSegmentID = form.firstSegmentID
        laterLegs = form.laterLegs.map { AgendaEditorLeg($0) }
        repeatCount = form.repeatCount
        repeatWeekdays = form.repeatWeekdays
        repeatEndsOn = form.repeatEndsOn
    }

    /// What the editor holds, as the form saves it.
    private func currentForm() -> PlannedJourneyForm {
        var form = PlannedJourneyForm(
            label: label,
            origin: origin.map { StationIdentity($0) },
            destination: destination.map { StationIdentity($0) },
            checklistText: checklistText,
            reminderMinutes: reminderMinutes,
            firstSegmentID: firstSegmentID,
            laterLegs: laterLegs.map(\.formLeg),
            repeatCount: repeatCount,
            repeatWeekdays: repeatWeekdays,
            repeatEndsOn: repeatEndsOn
        )
        if let departure {
            form.date = ItalianWallClock.date(of: departure)
            form.time = ItalianWallClock.time(of: departure)
        }
        return form
    }

    /// A template fills the editor with its one leg, time, lead and checklist.
    private func applyTemplate(_ template: PlannedJourneyTemplate) {
        var form = currentForm()
        form.apply(template, now: Date())
        origin = form.origin.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        destination = form.destination.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        departure = form.departureInstant
        laterLegs = []
        reminderMinutes = form.reminderMinutes
        checklistText = form.checklistText
        repeatCount = form.repeatCount
        repeatWeekdays = form.repeatWeekdays
        repeatEndsOn = form.repeatEndsOn
        tellTemplate(String.localizedStringWithFormat(NSLocalizedString("saved.agenda.template_applied", comment: ""), template.name))
    }

    /// «Salva come modello»: the one leg's stations and time, the lead and the checklist.
    private func saveTemplate() {
        guard let template = currentForm().template(id: UUID().uuidString, name: templateName) else {
            tellTemplate(NSLocalizedString("saved.agenda.template_invalid", comment: ""))
            return
        }
        model.saveAgendaTemplate(template)
        templateName = ""
        tellTemplate(NSLocalizedString("saved.agenda.template_saved", comment: ""))
    }

    private func deleteTemplate(_ template: PlannedJourneyTemplate) {
        model.deleteAgendaTemplate(id: template.id)
        tellTemplate(NSLocalizedString("saved.agenda.template_deleted", comment: ""))
    }

    private func tellTemplate(_ text: String) {
        templateMessage = text
        AgendaAnnouncement.post(text: text, .information)
    }

    private func save() {
        let form = currentForm()
        let now = Date()
        let journey: PlannedJourney
        switch form.journey(id: UUID().uuidString, now: now, editing: existing) {
        case let .failure(issue):
            show(AgendaText.messageKey(issue))
            return
        case let .success(built):
            journey = built
        }
        if form.needsPastDepartureWarning(now: now, editing: existing), !pastDepartureConfirmed {
            // Said once; the next «Salva» saves the journey, which goes straight to the archive.
            show("saved.agenda.departure_past")
            pastDepartureConfirmed = true
            return
        }
        if let refusal = model.saveJourney(journey, isNew: existing == nil) {
            show(AgendaText.messageKey(refusal))
            return
        }
        saving = true
        Task {
            let outcome = await model.scheduleReminder(afterSaving: journey)
            saving = false
            switch outcome {
            case .none, .scheduled:
                AgendaAnnouncement.post(AgendaText.savedMessageKey(outcome), .information)
                dismiss()
            case .notAllowed, .tooLate, .failed:
                // The journey is saved: the alert says why its reminder is not, then closes the editor.
                savedNoticeKey = AgendaText.savedMessageKey(outcome)
            }
        }
    }

    /// A message under the form, also said by VoiceOver at once.
    private func show(_ key: String) {
        messageKey = key
        AgendaAnnouncement.post(key, .error)
    }
}

/// A leg after the first in the editor; its departure is nil until chosen.
struct AgendaEditorLeg: Identifiable {
    let id = UUID()
    var origin: Station?
    var destination: Station?
    var departure: Date?
    var segmentID: String?

    init(origin: Station? = nil, destination: Station? = nil, departure: Date? = nil, segmentID: String? = nil) {
        self.origin = origin
        self.destination = destination
        self.departure = departure
        self.segmentID = segmentID
    }

    init(_ leg: PlannedJourneyFormLeg) {
        self.init(
            origin: leg.origin.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) },
            destination: leg.destination.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) },
            departure: leg.departureInstant,
            segmentID: leg.segmentID
        )
    }

    /// The leg as the form saves it, in Italian time.
    var formLeg: PlannedJourneyFormLeg {
        PlannedJourneyFormLeg(
            origin: origin.map { StationIdentity($0) },
            destination: destination.map { StationIdentity($0) },
            date: departure.flatMap { ItalianWallClock.date(of: $0) },
            time: departure.flatMap { ItalianWallClock.time(of: $0) },
            segmentID: segmentID
        )
    }
}

/// Legs 2 to 4 of the journey in the editor (Android's multi-leg editor): a new leg starts where
/// the last one arrives, and a leg's origin follows the destination of the leg before it while it
/// still showed that station; «Aggiungi tratta» adds one, up to four legs in all. Connection and
/// chronology are checked when the journey is saved.
struct AgendaEditorLaterLegs: View {
    @Binding var legs: [AgendaEditorLeg]
    /// The first leg's arrival and departure.
    let firstDestination: Station?
    let firstDeparture: Date?

    var body: some View {
        ForEach(Array(legs.enumerated()), id: \.element.id) { index, leg in
            Section {
                StationPickerField(titleKey: "planner.origin", selection: originSelection(leg.id))
                StationPickerField(titleKey: "planner.destination", selection: destinationSelection(leg.id))
                if let departure = leg.departure {
                    DatePicker("planner.departure", selection: Binding(
                        get: { legs.first(where: { $0.id == leg.id })?.departure ?? departure },
                        set: { date in setDeparture(date, of: leg.id) }
                    ))
                    .environment(\.timeZone, ItalianWallClock.timeZone)
                } else {
                    // The time is chosen anew: an hour after the leg before it, to adjust.
                    Button {
                        setDeparture(proposedDeparture(before: index), of: leg.id)
                    } label: {
                        LabeledContent("planner.departure") {
                            Text("saved.agenda.unset")
                        }
                    }
                }
                Button(role: .destructive) {
                    legs.removeAll { $0.id == leg.id }
                } label: {
                    Label("saved.agenda.remove_leg", systemImage: "minus.circle")
                }
            } header: {
                Text(verbatim: AgendaText.legTitle(index + 1, of: legs.count + 1))
            }
        }
        if legs.count + 1 < PlannedJourneySchema.maximumSegments {
            Section {
                Button {
                    legs.append(AgendaEditorLeg(origin: legs.last?.destination ?? firstDestination))
                } label: {
                    Label("saved.agenda.add_leg", systemImage: "plus.circle")
                }
            }
        }
    }

    private func originSelection(_ id: UUID) -> Binding<Station?> {
        Binding(
            get: { legs.first(where: { $0.id == id })?.origin },
            set: { station in
                guard let index = legs.firstIndex(where: { $0.id == id }) else { return }
                legs[index].origin = station
            }
        )
    }

    /// The next leg's origin follows while it still showed the previous destination.
    private func destinationSelection(_ id: UUID) -> Binding<Station?> {
        Binding(
            get: { legs.first(where: { $0.id == id })?.destination },
            set: { station in
                guard let index = legs.firstIndex(where: { $0.id == id }) else { return }
                let previous = legs[index].destination
                legs[index].destination = station
                if legs.indices.contains(index + 1), legs[index + 1].origin == previous {
                    legs[index + 1].origin = station
                }
            }
        )
    }

    private func setDeparture(_ date: Date?, of id: UUID) {
        guard let index = legs.firstIndex(where: { $0.id == id }) else { return }
        legs[index].departure = date
    }

    private func proposedDeparture(before index: Int) -> Date? {
        let previous = index == 0 ? firstDeparture : legs[index - 1].departure
        return previous.map { $0.addingTimeInterval(3_600) } ?? PlannedJourneyForm.new(now: Date()).departureInstant
    }
}

/// «Ricorrenza» in the editor (Android's rule): once, or 2, 4, 8 or 12 journeys in all on the
/// first date's weekday, which is always included, and the other weekdays chosen.
struct AgendaEditorRepetition: View {
    @Binding var count: Int
    @Binding var weekdays: Set<PlannedJourneyWeekday>
    /// The first departure's weekday; nil until a date is chosen.
    let startDay: PlannedJourneyWeekday?

    var body: some View {
        Section {
            Picker("saved.agenda.repeat", selection: $count) {
                ForEach(PlannedJourneyForm.repeatCounts(including: count), id: \.self) { choice in
                    Text(verbatim: AgendaText.repeatCount(choice)).tag(choice)
                }
            }
            if count > 1 {
                ForEach(PlannedJourneyWeekday.allCases, id: \.self) { day in
                    Toggle(isOn: Binding(
                        get: { day == startDay || weekdays.contains(day) },
                        set: { included in
                            if included {
                                weekdays.insert(day)
                            } else {
                                weekdays.remove(day)
                            }
                        }
                    )) {
                        Text(verbatim: AgendaText.weekdayName(day, full: true))
                    }
                    .disabled(day == startDay)
                }
            }
        } footer: {
            Text("saved.agenda.repeat_hint")
        }
    }
}

/// «Modelli di viaggio» in the editor (Android's templates, on this iPhone only): applying one fills
/// the editor with its leg, time, lead and checklist; a one-leg journey can be saved as one, a
/// template with the same name being replaced; swiping deletes one.
struct AgendaEditorTemplates: View {
    let templates: [PlannedJourneyTemplate]
    /// False for a journey of several legs: a template is one leg.
    let canSave: Bool
    @Binding var name: String
    let message: String?
    let apply: @MainActor (PlannedJourneyTemplate) -> Void
    let save: @MainActor () -> Void
    let delete: @MainActor (PlannedJourneyTemplate) -> Void

    var body: some View {
        Section {
            ForEach(templates) { template in
                Button {
                    apply(template)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: template.name)
                        Text(verbatim: "\(template.origin.displayName) → \(template.destination.displayName) · \(template.time.hhmm)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button(role: .destructive) {
                        delete(template)
                    } label: {
                        Label("saved.agenda.template_delete", systemImage: "trash")
                    }
                }
            }
            if canSave {
                TextField("saved.agenda.template_name", text: $name, prompt: Text("saved.agenda.template_placeholder"))
                Button("saved.agenda.template_save") { save() }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if let message {
                Text(verbatim: message)
                    .font(.footnote)
            }
        } header: {
            Text("saved.agenda.templates")
        } footer: {
            Text("saved.agenda.templates_hint")
        }
    }
}

/// VoiceOver announcements of the Agenda.
enum AgendaAnnouncement {
    /// Says the text of `key` when VoiceOver runs: an error at once, information after what is
    /// being said.
    @MainActor
    static func post(_ key: String, _ severity: PlannedJourneyFeedbackSeverity) {
        post(text: NSLocalizedString(key, comment: ""), severity)
    }

    /// Says `text`, already written in the user's language.
    @MainActor
    static func post(text: String, _ severity: PlannedJourneyFeedbackSeverity) {
        #if canImport(UIKit)
        let announcement = NSAttributedString(
            string: text,
            attributes: [.accessibilitySpeechQueueAnnouncement: severity.queuesBehindCurrentSpeech]
        )
        UIAccessibility.post(notification: .announcement, argument: announcement)
        #endif
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
    static func departure(_ departure: PlannedJourneyDateTime) -> String {
        "\(date(departure.date)) · \(departure.time.hhmm)"
    }

    /// «Tratta 2 di 3».
    static func legTitle(_ index: Int, of count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("saved.agenda.leg_title", comment: ""), index + 1, count)
    }

    /// «Tratte: 3».
    static func legCount(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("saved.agenda.legs_count", comment: ""), count)
    }

    /// «lun, gio · Viaggi in tutto: 4»: the weekdays from Monday, in the user's language.
    static func repetition(_ recurrence: PlannedJourneyRecurrence) -> String {
        weekdays(recurrence.weekdays) + " · "
            + String.localizedStringWithFormat(NSLocalizedString("saved.agenda.repeat_total", comment: ""), recurrence.occurrenceLimit)
    }

    static func weekdays(_ days: [PlannedJourneyWeekday]) -> String {
        days.sorted().map { weekdayName($0) }.joined(separator: ", ")
    }

    /// «Tra 1 h 10 min», «Tra 2 h» or «Tra 25 min».
    static func countdown(_ countdown: PlannedJourneyCountdown) -> String {
        if countdown.hours == 0 {
            return String.localizedStringWithFormat(NSLocalizedString("saved.agenda.countdown_minutes", comment: ""), countdown.minutes)
        }
        if countdown.minutes == 0 {
            return String.localizedStringWithFormat(NSLocalizedString("saved.agenda.countdown_hours", comment: ""), countdown.hours)
        }
        return String.localizedStringWithFormat(
            NSLocalizedString("saved.agenda.countdown_hours_minutes", comment: ""), countdown.hours, countdown.minutes
        )
    }

    /// The card's reminder line: the lead only when the system holds the reminder.
    static func readyReminder(_ state: PlannedJourneyReminderState) -> String {
        if case let .scheduled(minutes) = state { return reminderChoice(minutes) }
        return NSLocalizedString("saved.agenda.ready_no_reminder", comment: "")
    }

    static func checklistLeft(_ count: Int) -> String {
        guard count > 0 else { return NSLocalizedString("saved.agenda.ready_checklist_done", comment: "") }
        return String.localizedStringWithFormat(NSLocalizedString("saved.agenda.ready_checklist_left", comment: ""), count)
    }

    /// «novembre 2026», in the user's language.
    static func month(_ month: PlannedJourneyMonth) -> String {
        guard let first = month.firstDay, let midday = PlannedLocalTime(hour: 12, minute: 0),
              let instant = ItalianWallClock.instant(date: first, time: midday)
        else { return "\(month.year)-\(month.month)" }
        var style = Date.FormatStyle.dateTime.month(.wide).year()
        style.timeZone = ItalianWallClock.timeZone
        return instant.formatted(style)
    }

    /// «Una volta» or «Viaggi in tutto: 4».
    static func repeatCount(_ count: Int) -> String {
        guard count > 1 else { return NSLocalizedString("saved.agenda.repeat_once", comment: "") }
        return String.localizedStringWithFormat(NSLocalizedString("saved.agenda.repeat_total", comment: ""), count)
    }

    /// The name of a weekday in the user's language, short or in full.
    static func weekdayName(_ day: PlannedJourneyWeekday, full: Bool = false) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale.current
        let symbols = full ? calendar.weekdaySymbols : calendar.shortWeekdaySymbols
        // The calendar's symbols start on Sunday.
        let index = day.isoNumber % 7
        return symbols.indices.contains(index) ? symbols[index] : day.rawValue
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

    /// «Nessuno» or «15 min prima», the minutes written by the system in the user's language.
    static func reminderChoice(_ minutes: Int?) -> String {
        guard let minutes else { return NSLocalizedString("saved.agenda.reminder.none", comment: "") }
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.minute]
        formatter.unitsStyle = .short
        let duration = formatter.string(from: TimeInterval(minutes * 60)) ?? "\(minutes)"
        return String.localizedStringWithFormat(NSLocalizedString("saved.agenda.reminder.before", comment: ""), duration)
    }

    /// The line under a journey with a reminder; nil for «Nessuno».
    static func reminderStateKey(_ state: PlannedJourneyReminderState) -> String? {
        switch state {
        case .none: nil
        case .scheduled: "saved.agenda.reminder.scheduled"
        case .notAllowed: "saved.agenda.reminder.not_allowed"
        case .tooLate: "saved.agenda.reminder.too_late"
        case .notScheduled: "saved.agenda.reminder.not_scheduled"
        }
    }

    static func reminderSymbol(_ state: PlannedJourneyReminderState) -> String {
        if case .scheduled = state { return "bell" }
        return "bell.slash"
    }

    /// What is said once a journey is saved, by what became of its reminder.
    static func savedMessageKey(_ outcome: PlannedJourneyReminderOutcome) -> String {
        switch outcome {
        case .none: "saved.agenda.saved"
        case .scheduled: "saved.agenda.saved_with_reminder"
        case .notAllowed: "saved.agenda.saved_reminder_denied"
        case .tooLate: "saved.agenda.saved_reminder_too_late"
        case .failed: "saved.agenda.saved_reminder_failed"
        }
    }

    /// The app's page in the iPhone's settings, where notifications are allowed again.
    @MainActor
    static var notificationSettingsURL: URL? {
        #if canImport(UIKit)
        URL(string: UIApplication.openNotificationSettingsURLString)
        #else
        nil
        #endif
    }

    static func filePreviewTitleKey(_ preview: PlannedJourneyFilePreview?) -> String {
        if case .calendar = preview { return "saved.agenda.ics_preview_title" }
        return "saved.agenda.import_preview_title"
    }

    /// The counts of a picked file, in Android's words.
    static func filePreviewMessage(_ preview: PlannedJourneyFilePreview) -> String {
        switch preview {
        case let .journeys(journeys):
            String.localizedStringWithFormat(
                NSLocalizedString("saved.agenda.import_preview_body", comment: ""),
                journeys.accepted.count, journeys.duplicates.count, journeys.conflicts.count, journeys.unreadableRecords
            )
        case let .calendar(calendar):
            String.localizedStringWithFormat(
                NSLocalizedString("saved.agenda.ics_preview_body", comment: ""),
                calendar.matching.count, calendar.conflicting.count, calendar.unlinked.count
            )
        }
    }

    static func importMessageKey(_ refusal: PlannedJourneyRefusal) -> String {
        switch refusal {
        case .full: "saved.agenda.import_full"
        case .readOnly: "saved.agenda.read_only"
        case .duplicate, .notFound, .invalid, .storageFailure: "saved.agenda.storage_error"
        }
    }

    static func presetKey(_ preset: PlannedJourneyChecklistPreset) -> String {
        switch preset {
        case .commuter: "saved.agenda.preset.commuter"
        case .connection: "saved.agenda.preset.connection"
        case .bike: "saved.agenda.preset.bike"
        case .assistance: "saved.agenda.preset.assistance"
        }
    }

    static func presetSymbol(_ preset: PlannedJourneyChecklistPreset) -> String {
        switch preset {
        case .commuter: "briefcase"
        case .connection: "arrow.triangle.branch"
        case .bike: "bicycle"
        case .assistance: "figure.roll"
        }
    }

    /// A preset line in the user's language, stored as typed.
    static func checklistLine(_ line: PlannedJourneyChecklistLine) -> String {
        let key: String
        switch line {
        case .timeMargin: key = "saved.agenda.check.time_margin"
        case .documents: key = "saved.agenda.check.documents"
        case .belongings: key = "saved.agenda.check.belongings"
        case .connection: key = "saved.agenda.check.connection"
        case .bike: key = "saved.agenda.check.bike"
        case .assistance: key = "saved.agenda.check.assistance"
        }
        return NSLocalizedString(key, comment: "")
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
        case .disconnectedLegs: "saved.agenda.legs_disconnected"
        case .nonChronologicalLegs: "saved.agenda.legs_not_chronological"
        case .labelTooLong: "saved.agenda.label_too_long"
        case .checklistLineTooLong: "saved.agenda.checklist_too_long"
        case .sensitiveChecklist: "saved.agenda.checklist_sensitive"
        }
    }
}
